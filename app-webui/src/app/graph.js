function layoutGraph(payload) {
  const nodes = payload.nodes || [];
  const edges = payload.blueEdges || [];
  const nodeById = new Map(nodes.map((node) => [node.id, node]));
  const root = nodes.find((node) => node.type === 'ROOT');
  if (!root) {
    return;
  }

  const children = new Map();
  edges.forEach((edge) => {
    if (!children.has(edge.fromNodeId)) {
      children.set(edge.fromNodeId, []);
    }
    children.get(edge.fromNodeId).push(edge);
  });
  children.forEach((value) => value.sort((a, b) => a.fromPort - b.fromPort));

  const subtreeWidth = new Map();
  const siblingGap = 36;
  const depthGap = 92;
  const leftMargin = 40;
  const topMargin = 36;

  function measure(nodeId) {
    if (subtreeWidth.has(nodeId)) {
      return subtreeWidth.get(nodeId);
    }
    const node = nodeById.get(nodeId);
    if (!node) {
      return 0;
    }
    const childEdges = children.get(nodeId) || [];
    let width = node.width;
    if (childEdges.length > 0) {
      const totalChildrenWidth = childEdges
        .map((edge) => measure(edge.toNodeId))
        .reduce((sum, value) => sum + value, 0) + siblingGap * (childEdges.length - 1);
      width = Math.max(width, totalChildrenWidth);
    }
    subtreeWidth.set(nodeId, width);
    return width;
  }

  function place(nodeId, left, depth) {
    const node = nodeById.get(nodeId);
    if (!node) {
      return;
    }
    const width = subtreeWidth.get(nodeId) || node.width;
    node.x = left + (width - node.width) / 2;
    node.y = topMargin + depth * depthGap;

    const childEdges = children.get(nodeId) || [];
    let childLeft = left;
    childEdges.forEach((edge) => {
      const childWidth = subtreeWidth.get(edge.toNodeId) || 0;
      place(edge.toNodeId, childLeft, depth + 1);
      childLeft += childWidth + siblingGap;
    });
  }

  measure(root.id);
  place(root.id, leftMargin, 0);
}

function compactifyLambdaChains(model) {
  const nodeById = new Map(model.nodes.map((node) => [node.id, node]));
  const outgoingBlue = new Map();
  const incomingBlue = new Map();

  model.blueEdges.forEach((edge) => {
    if (!outgoingBlue.has(edge.fromNodeId)) {
      outgoingBlue.set(edge.fromNodeId, []);
    }
    outgoingBlue.get(edge.fromNodeId).push(edge);

    if (!incomingBlue.has(edge.toNodeId)) {
      incomingBlue.set(edge.toNodeId, []);
    }
    incomingBlue.get(edge.toNodeId).push(edge);
  });
  outgoingBlue.forEach((list) => list.sort((a, b) => a.fromPort - b.fromPort));

  function firstBlueEdge(nodeId, port) {
    const edges = outgoingBlue.get(nodeId) || [];
    return edges.find((edge) => edge.fromPort === port);
  }

  const processed = new Set();
  const removeNodeIds = new Set();
  const removeBlueEdgeIds = new Set();
  const edgeUpdates = [];
  let lambdaCompactEdgeIndex = 0;

  model.nodes.forEach((node) => {
    if (node.type !== 'LAMBDA' || processed.has(node.id)) {
      return;
    }

    const incoming = incomingBlue.get(node.id) || [];
    const hasLambdaParent = incoming.some((edge) => {
      const parent = nodeById.get(edge.fromNodeId);
      return parent && parent.type === 'LAMBDA' && edge.fromPort === 0;
    });
    if (hasLambdaParent) {
      return;
    }

    const chain = [];
    let currentId = node.id;
    while (true) {
      const current = nodeById.get(currentId);
      if (!current || current.type !== 'LAMBDA' || processed.has(currentId)) {
        break;
      }
      chain.push(currentId);
      processed.add(currentId);

      const nextEdge = firstBlueEdge(currentId, 0);
      if (!nextEdge) {
        break;
      }
      const nextNode = nodeById.get(nextEdge.toNodeId);
      if (!nextNode || nextNode.type !== 'LAMBDA') {
        break;
      }
      currentId = nextNode.id;
    }

    if (chain.length <= 1) {
      return;
    }

    const topId = chain[0];
    const deepestId = chain[chain.length - 1];
    const topNode = nodeById.get(topId);
    const deepestBodyEdge = firstBlueEdge(deepestId, 0);
    if (!topNode || !deepestBodyEdge) {
      return;
    }

    const chainBinders = [];
    const binderPortByNodeId = new Map();
    chain.forEach((lambdaId) => {
      const lambdaNode = nodeById.get(lambdaId);
      if (!lambdaNode) {
        return;
      }
      binderPortByNodeId.set(lambdaId, chainBinders.length);
      chainBinders.push(lambdaNode.label || '_');
    });

    topNode.greenOutputCount = chainBinders.length;
    topNode.label = chainBinders.join(', ');
    if (topNode.label) {
      topNode.width = Math.max(topNode.width, 24 + topNode.label.length * 7.2, 24 + topNode.greenOutputCount * 16);
    }

    chain.forEach((lambdaId, index) => {
      const outEdges = outgoingBlue.get(lambdaId) || [];
      outEdges.forEach((edge) => removeBlueEdgeIds.add(edge.id));
      if (index > 0) {
        removeNodeIds.add(lambdaId);
      }
    });

    edgeUpdates.push({
      id: `lb${lambdaCompactEdgeIndex++}`,
      fromNodeId: topId,
      toNodeId: deepestBodyEdge.toNodeId,
      fromPort: 0,
      toPort: deepestBodyEdge.toPort,
    });

    model.greenEdges = model.greenEdges.flatMap((edge) => {
      if (!chain.includes(edge.fromNodeId)) {
        return [edge];
      }

      const mappedPort = binderPortByNodeId.get(edge.fromNodeId);
      if (mappedPort === undefined) {
        return [];
      }

      return [{
        ...edge,
        fromNodeId: topId,
        fromPort: mappedPort,
      }];
    });
  });

  model.nodes = model.nodes.filter((node) => !removeNodeIds.has(node.id));
  model.blueEdges = model.blueEdges
    .filter((edge) => !removeBlueEdgeIds.has(edge.id))
    .filter((edge) => !removeNodeIds.has(edge.fromNodeId) && !removeNodeIds.has(edge.toNodeId));
  model.blueEdges.push(...edgeUpdates);
  model.greenEdges = model.greenEdges
    .filter((edge) => !removeNodeIds.has(edge.fromNodeId) && !removeNodeIds.has(edge.toNodeId));
}

function compactify(payload, compactEnabled) {
  const model = {
    ...payload,
    nodes: (payload.nodes || []).map((node) => ({ ...node })),
    blueEdges: (payload.blueEdges || []).map((edge) => ({ ...edge })),
    greenEdges: (payload.greenEdges || []).map((edge) => ({ ...edge })),
  };

  if (!compactEnabled) {
    return model;
  }

  const nodeById = new Map(model.nodes.map((node) => [node.id, node]));
  const outgoing = new Map();
  const incoming = new Map();

  model.blueEdges.forEach((edge) => {
    if (!outgoing.has(edge.fromNodeId)) {
      outgoing.set(edge.fromNodeId, []);
    }
    outgoing.get(edge.fromNodeId).push(edge);

    if (!incoming.has(edge.toNodeId)) {
      incoming.set(edge.toNodeId, []);
    }
    incoming.get(edge.toNodeId).push(edge);
  });

  outgoing.forEach((list) => list.sort((a, b) => a.fromPort - b.fromPort));

  const processed = new Set();
  const removeNodeIds = new Set();
  const removeEdgeIds = new Set();
  const newEdges = [];
  let compactEdgeIndex = 0;

  function firstEdgeByPort(nodeId, port) {
    const edges = outgoing.get(nodeId) || [];
    return edges.find((edge) => edge.fromPort === port);
  }

  model.nodes.forEach((node) => {
    if (node.type !== 'APP' || processed.has(node.id)) {
      return;
    }

    const incomingEdges = incoming.get(node.id) || [];
    const hasParentFnSpine = incomingEdges.some((edge) => {
      const parent = nodeById.get(edge.fromNodeId);
      return parent && parent.type === 'APP' && edge.fromPort === 0;
    });

    if (hasParentFnSpine) {
      return;
    }

    const chain = [];
    let currentId = node.id;
    while (true) {
      const current = nodeById.get(currentId);
      if (!current || current.type !== 'APP' || processed.has(currentId)) {
        break;
      }
      chain.push(currentId);
      processed.add(currentId);

      const fnEdge = firstEdgeByPort(currentId, 0);
      if (!fnEdge) {
        break;
      }
      const fnTarget = nodeById.get(fnEdge.toNodeId);
      if (!fnTarget || fnTarget.type !== 'APP') {
        break;
      }
      currentId = fnTarget.id;
    }

    if (chain.length <= 1) {
      return;
    }

    const topId = chain[0];
    const deepestId = chain[chain.length - 1];
    const topNode = nodeById.get(topId);
    const deepestFnEdge = firstEdgeByPort(deepestId, 0);
    if (!topNode || !deepestFnEdge) {
      return;
    }

    const argTargets = [];
    [...chain].reverse().forEach((appId) => {
      const argEdge = firstEdgeByPort(appId, 1);
      if (argEdge) {
        argTargets.push(argEdge.toNodeId);
      }
    });

    const outputTargets = [deepestFnEdge.toNodeId, ...argTargets];
    topNode.blueOutputCount = outputTargets.length;
    topNode.width = Math.max(48, 22 + outputTargets.length * 20);

    chain.forEach((appId, idx) => {
      const outEdges = outgoing.get(appId) || [];
      outEdges.forEach((edge) => removeEdgeIds.add(edge.id));
      if (idx > 0) {
        removeNodeIds.add(appId);
      }
    });

    outputTargets.forEach((targetId, port) => {
      newEdges.push({
        id: `cb${compactEdgeIndex++}`,
        fromNodeId: topId,
        toNodeId: targetId,
        fromPort: port,
        toPort: 0,
      });
    });
  });

  model.nodes = model.nodes.filter((node) => !removeNodeIds.has(node.id));
  model.blueEdges = model.blueEdges
    .filter((edge) => !removeEdgeIds.has(edge.id))
    .filter((edge) => !removeNodeIds.has(edge.fromNodeId) && !removeNodeIds.has(edge.toNodeId));
  model.blueEdges.push(...newEdges);
  model.greenEdges = model.greenEdges
    .filter((edge) => !removeNodeIds.has(edge.fromNodeId) && !removeNodeIds.has(edge.toNodeId));

  compactifyLambdaChains(model);
  layoutGraph(model);

  return model;
}

window.DeltaGraph = {
  compactify,
};
