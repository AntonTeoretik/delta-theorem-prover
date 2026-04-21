package app

import app.ui.MainWindow
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main() {
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())

    SwingUtilities.invokeLater {
        MainWindow().isVisible = true
    }
}
