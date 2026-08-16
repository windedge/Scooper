package scooper.util

import org.slf4j.LoggerFactory
import java.awt.Font
import java.awt.Image
import java.awt.Rectangle
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

private val logger by lazy { LoggerFactory.getLogger("TrayManager") }

/**
 * Manages a system tray icon with a styled popup menu.
 *
 * Uses AWT [TrayIcon] for the system tray presence and a Swing [JPopupMenu]
 * for the right-click context menu, allowing custom font/size/padding.
 */
class TrayManager(
    private val icon: Image,
    private val showIcon: Icon? = null,
    private val exitIcon: Icon? = null,
    private val onShow: () -> Unit,
    private val onExit: () -> Unit,
) {
    private var trayIcon: TrayIcon? = null

    private val popupMenu = JPopupMenu().apply {
        border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(java.awt.Color(200, 200, 200), 1),
            javax.swing.BorderFactory.createEmptyBorder(4, 0, 4, 0),
        )
        val menuFont = Font("SansSerif", Font.PLAIN, 12)
        add(JMenuItem(tr("Show Scooper"), showIcon).apply {
            font = menuFont
            preferredSize = java.awt.Dimension(
                preferredSize.width.coerceAtLeast(140),
                28,
            )
            addActionListener { onShow() }
        })
        addSeparator()
        add(JMenuItem(tr("Exit"), exitIcon).apply {
            font = menuFont
            preferredSize = java.awt.Dimension(
                preferredSize.width.coerceAtLeast(140),
                28,
            )
            addActionListener { onExit() }
        })
    }

    private fun handlePopupTrigger(e: MouseEvent) {
        if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
            val mouseLoc = java.awt.MouseInfo.getPointerInfo().location
            showPopupMenu(mouseLoc.x, mouseLoc.y)
        }
    }

    fun install() {
        if (!SystemTray.isSupported()) {
            logger.warn("System tray is not supported on this platform")
            return
        }
        if (trayIcon != null) return

        val tray = SystemTray.getSystemTray()
        val trayIcon = TrayIcon(icon, "Scooper").apply {
            isImageAutoSize = true
            addActionListener { onShow() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) = handlePopupTrigger(e)
                override fun mousePressed(e: MouseEvent) = handlePopupTrigger(e)
            })
        }
        try {
            tray.add(trayIcon)
            this.trayIcon = trayIcon
            logger.info("Tray icon installed")
        } catch (e: Exception) {
            logger.error("Failed to add tray icon", e)
        }
    }

    /**
     * Show [JPopupMenu] at the given screen coordinates.
     *
     * Creates a [JWindow] at the click position as the invoker.
     * Uses [SwingUtilities.invokeLater] to ensure the invoker is showing
     * before displaying the popup.
     */
    private fun showPopupMenu(screenX: Int, screenY: Int) {
        val invoker = JWindow().apply {
            bounds = Rectangle(screenX - 8, screenY - 8, 16, 16)
            isAlwaysOnTop = true
        }
        invoker.isVisible = true

        SwingUtilities.invokeLater {
            popupMenu.pack()
            popupMenu.show(invoker, 0, -popupMenu.preferredSize.height)
        }

        val listener = object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {}
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {
                popupMenu.removePopupMenuListener(this)
                invoker.dispose()
            }
            override fun popupMenuCanceled(e: PopupMenuEvent?) {
                popupMenu.removePopupMenuListener(this)
                invoker.dispose()
            }
        }
        popupMenu.addPopupMenuListener(listener)
    }

    fun remove() {
        trayIcon?.let {
            try {
                SystemTray.getSystemTray().remove(it)
                logger.info("Tray icon removed")
            } catch (e: Exception) {
                logger.warn("Failed to remove tray icon", e)
            }
        }
        trayIcon = null
    }
}