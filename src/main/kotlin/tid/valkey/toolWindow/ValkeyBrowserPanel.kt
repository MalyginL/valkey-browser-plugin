package tid.valkey.toolWindow

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.text.SimpleDateFormat
import java.util.Date
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import com.intellij.ui.components.JBLabel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import javax.swing.JScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBUI.insetsBottom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tid.valkey.valkey.SavedConnectionConfig
import tid.valkey.valkey.ValkeyConnection
import tid.valkey.valkey.ValkeyService
import tid.valkey.valkey.ValkeySettings
import redis.clients.jedis.exceptions.JedisConnectionException
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JProgressBar
import javax.swing.JTextArea
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.border.CompoundBorder
import javax.swing.border.LineBorder
import javax.swing.border.TitledBorder
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener
import java.awt.geom.Ellipse2D
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.KeyEvent
import java.awt.Dimension
import java.awt.Font
import java.util.ResourceBundle

/**
 * Holds a key name alongside its TTL for display in the key list.
 */
data class KeyWithTTL(val name: String, val ttl: Long)

/**
 * Renders a circular dot for the health indicator.
 */
class RoundPanel(private val radius: Int) : JBPanel<Nothing>() {
    init {
        isOpaque = false
        preferredSize = Dimension(radius * 2, radius * 2)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = background
        g2.fill(Ellipse2D.Double(0.0, 0.0, (radius * 2).toDouble(), (radius * 2).toDouble()))
    }
}

/**
 * Main UI panel for the Valkey Browser tool window.
 */
class ValkeyBrowserPanel(
    private val project: Project
) : JBPanel<ValkeyBrowserPanel>() {

    companion object {
        private val bundle = ResourceBundle.getBundle("messages.ValkeyBundle")
        private fun message(key: String, vararg args: Any): String {
            val template = bundle.getString(key)
            return if (args.isEmpty()) template else java.text.MessageFormat.format(template, *args)
        }

        // ── Theme-aware color helpers ──
        private fun panelBackground() = UIUtil.getPanelBackground()
        private fun panelForeground() = UIUtil.getLabelForeground()
        private fun borderLine() = UIUtil.getTableGridColor() ?: UIUtil.getPanelBackground().darker()
       private fun mutedForeground() = UIUtil.getInactiveTextColor()
        private fun smallFont() = UIManager.getFont("Label.font") ?: JBLabel().font
        private fun fixedFont() = UIManager.getFont("TextField.font") ?: JTextArea().font
        private fun accentColor(): java.awt.Color = UIManager.getColor("Link.activeForeground") ?: JBColor(0x3498DB, 0x3498DB)
        private fun successColor() = JBColor.GREEN
        private fun warningColor() = JBColor.ORANGE

        /** Blend fg over bg with given alpha (0..1). */
        private fun blend(fg: java.awt.Color, bg: java.awt.Color, alpha: Float): java.awt.Color {
            val a = alpha.coerceIn(0f, 1f)
            val r = (fg.red * a + bg.red * (1 - a)).toInt().coerceIn(0, 255)
            val g = (fg.green * a + bg.green * (1 - a)).toInt().coerceIn(0, 255)
            val b = (fg.blue * a + bg.blue * (1 - a)).toInt().coerceIn(0, 255)
            return java.awt.Color(r, g, b)
        }

        private fun toolbarHoverBackground() = blend(UIUtil.getListSelectionBackground(), UIUtil.getPanelBackground(), 0.08f)

        /** Style a text field to look like a placeholder hint (italic + muted). */
        private fun applyPlaceholderStyle(field: javax.swing.text.JTextComponent) {
            field.font = field.font.deriveFont(Font.ITALIC)
            field.foreground = mutedForeground()
        }

        /** Clear placeholder styling when the field gains focus. */
        private fun clearPlaceholderStyle(field: javax.swing.text.JTextComponent) {
            field.font = field.font.deriveFont(Font.PLAIN)
            field.foreground = panelForeground()
        }
    }

    private val service: ValkeyService = project.service<ValkeyService>()

    init {
        service.logCallback = { level, msg ->
            appendLog(level, msg)
        }
    }
    private val settings: ValkeySettings = project.service<ValkeySettings>()
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    // Connection management
    private val connectionListModel = DefaultListModel<String>()
    private val connectionList = JBList(connectionListModel).apply {
            fixedCellHeight = 22
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: javax.swing.JList<*>,
                    value: Any,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): java.awt.Component {
                    val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    (c as? javax.swing.JLabel)?.font = c.font?.deriveFont(Font.ITALIC)
                    if (c is javax.swing.JComponent) {
                        c.border = JBUI.Borders.emptyLeft(8)
                    }
                    return c
                }
            }
        }

    // Connection list selection listener (registered once in init)
    private val connectionSelectionListener: ListSelectionListener = ListSelectionListener { e ->
        if (e?.valueIsAdjusting == true) return@ListSelectionListener
        val selectedName = connectionList.selectedValue ?: return@ListSelectionListener
        val config = settings.savedConnections.find { it.name == selectedName }
        if (config != null) {
            populateFormFromConfig(config)
        }
    }

    // Connection form fields
    private lateinit var hostField: JBTextField
    private lateinit var portField: JBTextField
    private val dbField = JComboBox<Int>(arrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)).apply {
            maximumRowCount = 8
        }
    private lateinit var sslButton: JBCheckBox
    private lateinit var usernameField: JBTextField
    private lateinit var passwordField: javax.swing.JPasswordField

    // Key list — stores KeyWithTTL, renders with TTL
    private val keyListModel = DefaultListModel<KeyWithTTL>()
    private val keyList = JBList(keyListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = KeyCellRenderer()
        isOpaque = true
        background = panelBackground()
    }

    // Pattern filter — server-side glob matching
    private val patternField = JBTextField()
    private val limitField = JBTextField("100")
    private var cachedDbSize: Long = -1L
    private var cachedMemory: String = "?"

    // Stats label — theme-aware
    private val statsLabel = JBLabel("").apply {
        font = smallFont().deriveFont(Font.ITALIC)
        foreground = mutedForeground()
        isOpaque = true
        background = panelBackground()
        border = CompoundBorder(
            LineBorder(borderLine(), 1),
            JBUI.Borders.empty(4, 8)
        )
    }

    // In-panel log area
    private val logLines = StringBuilder()
    private val logArea = JBTextArea().apply {
        isEditable = false
        isFocusable = false
        lineWrap = false
        wrapStyleWord = false
        font = fixedFont()
        isOpaque = true
        background = panelBackground()
        foreground = panelForeground()
    }
      private val logPanel = JBPanel<Nothing>(java.awt.BorderLayout()).apply {
        isOpaque = true
        background = panelBackground()
        border = TitledBorder(
            LineBorder(borderLine(), 1),
            message("valkey.browser.section.log"),
            TitledBorder.LEFT,
            TitledBorder.TOP,
            smallFont().deriveFont(Font.BOLD),
            panelForeground()
        )
        add(JBScrollPane(logArea).apply {
            isOpaque = true
            background = panelBackground()
            viewport.isOpaque = true
            viewport.background = panelBackground()
            border = JBUI.Borders.empty(0, 8, 8, 8)
            verticalScrollBar.background = panelBackground()
            verticalScrollBar.isOpaque = true
            horizontalScrollBar.background = panelBackground()
            horizontalScrollBar.isOpaque = true
            val logLineHeight = logArea.getFontMetrics(logArea.font).height
            preferredSize = Dimension(600, logLineHeight * 3 + 16)
            minimumSize = Dimension(600, logLineHeight * 3 + 16)
        }, java.awt.BorderLayout.CENTER)
        isVisible = true
    }

    // Log buffer — always visible, appends to StringBuilder then sets full text on EDT
    private val logTimestamp = SimpleDateFormat("HH:mm:ss")
    private fun appendLog(level: String, msg: String) {
        val entry = "[$level] ${logTimestamp.format(Date())} $msg"
        SwingUtilities.invokeLater {
            if (logLines.isNotEmpty()) logLines.appendLine()
            logLines.append(entry)
            logArea.text = logLines.toString()
            logArea.caretPosition = 0
            // Auto-scroll scrollbar to bottom
            val scrollRect = logArea.modelToView(logArea.document.length)
            if (scrollRect != null) logArea.scrollRectToVisible(scrollRect)
        }
    }
    private fun logInfo(msg: String) {
        thisLogger().info(msg)
        appendLog("INFO", msg)
    }
    private fun logWarn(msg: String) {
        thisLogger().warn(msg)
        appendLog("WARN", msg)
    }
    private fun logError(msg: String, t: Throwable? = null) {
        thisLogger().error(msg, t)
        appendLog("ERROR", msg)
    }

    // Loading indicator
    private val loadingIndicator = JProgressBar().apply {
        isIndeterminate = true
        isBorderPainted = false
        isStringPainted = false
        isVisible = false
        preferredSize = Dimension(preferredSize.width, 16)
    }

    // Toolbar buttons (compact, no border, icon-style with hover)
    private fun createToolButton(text: String, icon: String = "", action: () -> Unit): JButton {
        return JButton("$icon $text").apply {
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
            font = smallFont()
            margin = JBUI.insets(4, 8)
            addActionListener { action() }

            // Theme-aware hover effect
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                    background = toolbarHoverBackground()
                    isOpaque = true
                    repaint()
                }
                override fun mouseExited(e: java.awt.event.MouseEvent?) {
                    isOpaque = false
                    repaint()
                }
            })
        }
    }

    // Keys2 toolbar buttons (class-level for updateToolbarButtons)
    private val keys2RefreshBtn = createToolButton("Scan", "⟳") { loadKeys() }
    private val keys2CreateBtn = createToolButton("Create", "＋") { showCreateKeyDialog() }
    private val keys2DeleteBtn = createToolButton(message("valkey.browser.action.delete.key"), "🗑") { handleDeleteKey() }

    private fun updateToolbarButtons() {
        val hasSelection = keyList.selectedValue != null
        keys2DeleteBtn.isEnabled = hasSelection
    }

    // Context menu
    private val contextMenu = JPopupMenu().apply {
        add(createMenuItem(message("valkey.browser.action.refresh")) { loadKeys() })
        add(createMenuItem(message("valkey.browser.action.delete.key")) { handleDeleteKey() })
    }

    private fun createMenuItem(text: String, action: () -> Unit): JMenuItem {
        return JMenuItem(text).apply {
            addActionListener { action() }
        }
    }

    // Value viewer — mutable TitledBorder for type display
    private val valueBorder = TitledBorder(
        LineBorder(borderLine(), 1),
        message("valkey.browser.section.value"),
        TitledBorder.LEFT,
        TitledBorder.TOP,
        smallFont().deriveFont(Font.BOLD),
        panelForeground()
    )
    private val valueArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = fixedFont()
        isOpaque = true
        background = panelBackground()
        foreground = panelForeground()
    }

    // Connect/Disconnect button
    private val actionLink = LinkLabel<String>(message("valkey.browser.action.connect"), null).apply {
        setListener({ _, _ -> handleConnection() }, null)
    }

   // Health indicator (circular dot + label)
    private val healthDot = RoundPanel(7).apply {
        border = JBUI.Borders.emptyRight(6)
        background = mutedForeground()
    }
    private val healthLabel = JBLabel(message("valkey.browser.status.disconnected")).apply {
        font = smallFont().deriveFont(Font.BOLD)
        foreground = mutedForeground()
    }

    init {
        layout = java.awt.BorderLayout()
        border = JBUI.Borders.empty(8)
        isOpaque = true
        background = panelBackground()

        // Connection form panel with TitledBorder
        val connPanel = JBPanel<Nothing>(java.awt.BorderLayout()).apply {
            isOpaque = true
            background = panelBackground()
            border = TitledBorder(
                LineBorder(borderLine(), 1),
                message("valkey.browser.section.connection"),
                TitledBorder.LEFT,
                TitledBorder.TOP,
                smallFont().deriveFont(Font.BOLD),
                panelForeground()
            )
            add(panel { connectionForm() }.apply {
                isOpaque = true
                background = panelBackground()
            }, java.awt.BorderLayout.CENTER)
        }

        // Keys panel with TitledBorder
        val keys2Panel = JBPanel<Nothing>(java.awt.BorderLayout()).apply {
            isOpaque = true
            background = panelBackground()
            border = TitledBorder(
                LineBorder(borderLine(), 1),
                message("valkey.browser.section.keys2"),
                TitledBorder.LEFT,
                TitledBorder.TOP,
                smallFont().deriveFont(Font.BOLD),
                panelForeground()
            )

            // Body: toolbar + scroll list
            val body = JBPanel<Nothing>(java.awt.BorderLayout()).apply {
                isOpaque = true
                background = panelBackground()
                border = JBUI.Borders.empty(4, 8)

                // Toolbar: pattern row + limit/scan row
                val toolbar = JBPanel<Nothing>(java.awt.BorderLayout()).apply {
                    isOpaque = true
                    background = panelBackground()

                    // Top: pattern field
                    val patternRow = JBPanel<Nothing>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4)).apply {
                        isOpaque = false
                        add(JBLabel(message("valkey.browser.label.scan.pattern")).apply {
                            font = smallFont()
                            foreground = panelForeground()
                        })
                        add(patternField.apply { columns = 20; toolTipText = message("valkey.browser.label.scan.pattern.tooltip") })
                    }
                    add(patternRow, java.awt.BorderLayout.NORTH)

                    // Bottom: limit + Scan + Delete + loading
                    val btnRow = JBPanel<Nothing>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 4)).apply {
                        isOpaque = false
                        add(JBLabel(message("valkey.browser.label.limit")).apply {
                            font = smallFont().deriveFont(Font.ITALIC)
                            foreground = mutedForeground()
                        })
                        add(limitField.apply {
                            columns = 5
                            toolTipText = message("valkey.browser.label.limit.tooltip")
                            font = smallFont().deriveFont(Font.ITALIC)
                            foreground = mutedForeground()
                        })
                        add(keys2RefreshBtn.apply { border = LineBorder(borderLine(), 1) })
                        add(Box.createHorizontalGlue())
                        add(loadingIndicator)
                    }
                    add(btnRow, java.awt.BorderLayout.SOUTH)
                }
                add(toolbar, java.awt.BorderLayout.NORTH)

                // Key list with delete button on the right
                val listWithActions = JBPanel<Nothing>(java.awt.BorderLayout()).apply {
                    isOpaque = true
                    background = panelBackground()
                    add(JBScrollPane(keyList).apply {
                        isOpaque = true
                        background = panelBackground()
                        viewport.isOpaque = true
                        viewport.background = panelBackground()
                        border = JBUI.Borders.emptyTop(2)
                        verticalScrollBar.background = panelBackground()
                        verticalScrollBar.isOpaque = true
                        horizontalScrollBar.background = panelBackground()
                        horizontalScrollBar.isOpaque = true
                    }, java.awt.BorderLayout.CENTER)
                    add(JBPanel<Nothing>(java.awt.FlowLayout()).apply {
                        isOpaque = false
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        add(keys2DeleteBtn)
                        add(keys2CreateBtn.apply { border = LineBorder(borderLine(), 1) })
                    }, java.awt.BorderLayout.EAST)
                }
                add(listWithActions, java.awt.BorderLayout.CENTER)
            }
            add(body, java.awt.BorderLayout.CENTER)
            add(statsLabel, java.awt.BorderLayout.SOUTH)
        }

        // Context menu on key list (right-click)
        keyList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (e.button == java.awt.event.MouseEvent.BUTTON3) {
                    updateContextMenu()
                    contextMenu.show(keyList, e.x, e.y)
                }
            }
        })

        // Keyboard shortcuts
        keyList.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.keyCode == KeyEvent.VK_F5 -> {
                        e.consume()
                        loadKeys()
                    }
                    e.keyCode == KeyEvent.VK_DELETE -> {
                        e.consume()
                        handleDeleteKey()
                    }
                    e.isControlDown && e.keyCode == KeyEvent.VK_C && !e.isShiftDown -> {
                        // Default copy already works for selected text, skip
                    }
                    }
            }
        })

        // Listen for key selection
        keyList.addListSelectionListener(ListSelectionListener { e: ListSelectionEvent? ->
            if (e == null || e.valueIsAdjusting) return@ListSelectionListener
            loadSelectedKey()
            updateToolbarButtons()
        })

        // Initialize toolbar button states
        updateToolbarButtons()

        // Value viewer panel with TitledBorder
        val valuePanel = JBPanel<Nothing>(java.awt.BorderLayout()).apply {
            isOpaque = true
            background = panelBackground()
            border = valueBorder
            add(JBScrollPane(valueArea).apply {
                isOpaque = true
                background = panelBackground()
                viewport.isOpaque = true
                viewport.background = panelBackground()
                border = JBUI.Borders.empty(0, 8, 8, 8)
                verticalScrollBar.background = panelBackground()
                verticalScrollBar.isOpaque = true
                horizontalScrollBar.background = panelBackground()
                horizontalScrollBar.isOpaque = true
            }, java.awt.BorderLayout.CENTER)
        }

        // Stack Connection → Keys → Value vertically
        val mainStack = JBPanel<Nothing>(java.awt.GridBagLayout()).apply {
            isOpaque = true
            background = panelBackground()

            // Connection (fixed height, row 0)
            val connGbc = java.awt.GridBagConstraints().apply {
                gridx = 0; gridy = 0
                fill = java.awt.GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = insetsBottom(8)
            }
            add(connPanel, connGbc)

            // Keys (flexible, row 1)
            val keysGbc = java.awt.GridBagConstraints().apply {
                gridx = 0; gridy = 1
                fill = java.awt.GridBagConstraints.BOTH
                weightx = 1.0
                weighty = 0.5
            }
            add(keys2Panel, keysGbc)

            // Divider (row 2)
            val divider = JBPanel<Nothing>().apply {
                isOpaque = true
                background = borderLine()
                minimumSize = Dimension(1, 2)
                preferredSize = Dimension(1, 2)
                maximumSize = Dimension(Integer.MAX_VALUE, 2)
            }
            val divGbc = java.awt.GridBagConstraints().apply {
                gridx = 0; gridy = 2
                fill = java.awt.GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = JBUI.insets(4, 0)
            }
            add(divider, divGbc)

            // Value (flexible, row 3)
            val valueGbc = java.awt.GridBagConstraints().apply {
                gridx = 0; gridy = 3
                fill = java.awt.GridBagConstraints.BOTH
                weightx = 1.0
                weighty = 0.5
            }
            add(valuePanel, valueGbc)

            // Log panel (hidden by default, row 4)
            val logDivider = JBPanel<Nothing>().apply {
                isOpaque = true
                background = borderLine()
                minimumSize = Dimension(1, 2)
                preferredSize = Dimension(1, 2)
                maximumSize = Dimension(Integer.MAX_VALUE, 2)
            }
            val logDivGbc = java.awt.GridBagConstraints().apply {
                gridx = 0; gridy = 4
                fill = java.awt.GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = JBUI.insets(4, 0)
            }
            add(logDivider, logDivGbc)

            val logGbc = java.awt.GridBagConstraints().apply {
                gridx = 0; gridy = 5
                fill = java.awt.GridBagConstraints.BOTH
                weightx = 1.0
                weighty = 0.0
                insets = JBUI.insetsTop(4)
            }
            add(logPanel, logGbc)
        }
        add(mainStack, java.awt.BorderLayout.CENTER)

        // Load saved connections on init
        loadSavedConnections()

        // Register connection list selection listener once
        connectionList.addListSelectionListener(connectionSelectionListener)

        // Populate form from current saved connection
        populateFormFromSettings()
    }

    private fun Panel.connectionForm() {
        // Connection selector + Save/Delete buttons (stacked vertically)
        row {
            cell(JBScrollPane(connectionList).apply {
                border = LineBorder(borderLine(), 1)
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                preferredSize = Dimension(280, 110)
            })
            cell(JBPanel<Nothing>(java.awt.GridLayout(2, 0, 0, 4)).apply {
                val saveBtn = JButton(message("valkey.browser.action.save.connection")).apply {
                    addActionListener { handleSaveConnection() }
                }
                val deleteBtn = JButton("✕").apply {
                    toolTipText = message("valkey.browser.action.delete.connection")
                    addActionListener { handleDeleteConnection() }
                }
                add(saveBtn)
                add(deleteBtn)
            })
        }

        // Separator between connection block and form fields
        row {
            cell(JBPanel<Nothing>(java.awt.BorderLayout()).apply {
                isOpaque = true
                background = panelBackground()
                border = JBUI.Borders.empty(4, 0)
                add(JBPanel<Nothing>().apply {
                    isOpaque = true
                    background = borderLine()
                    minimumSize = Dimension(1, 1)
                    preferredSize = Dimension(1, 1)
                    maximumSize = Dimension(Integer.MAX_VALUE, 1)
                }, java.awt.BorderLayout.CENTER)
            })
        }

        // Host + Port + DB
        row {
            label(message("valkey.browser.label.host"))
            hostField = textField().component
            hostField.text = "localhost"
            hostField.columns = 8
            applyPlaceholderStyle(hostField)
            hostField.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent?) = clearPlaceholderStyle(hostField)
                override fun focusLost(e: java.awt.event.FocusEvent?) {
                    if (hostField.text.isEmpty()) applyPlaceholderStyle(hostField)
                }
            })

            label(message("valkey.browser.label.port"))
            portField = textField().component
            portField.text = "6379"
            portField.columns = 5
            applyPlaceholderStyle(portField)
            portField.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent?) = clearPlaceholderStyle(portField)
                override fun focusLost(e: java.awt.event.FocusEvent?) {
                    if (portField.text.isEmpty()) applyPlaceholderStyle(portField)
                }
            })

            label(message("valkey.browser.label.db"))
            cell(dbField).apply { component.preferredSize = Dimension(36, component.preferredSize.height) }
        }

        // Username + Password
        row {
            label(message("valkey.browser.label.username"))
            usernameField = textField().component
            usernameField.text = "default"
            usernameField.columns = 8
            applyPlaceholderStyle(usernameField)
            usernameField.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent?) = clearPlaceholderStyle(usernameField)
                override fun focusLost(e: java.awt.event.FocusEvent?) {
                    if (usernameField.text.isEmpty()) applyPlaceholderStyle(usernameField)
                }
            })

            label(message("valkey.browser.label.password"))
            passwordField = passwordField().component as javax.swing.JPasswordField
            passwordField.columns = 10
        }

        // SSL + Connect/Disconnect + Health
        row {
            sslButton = checkBox(message("valkey.browser.checkbox.ssl")).component
            cell(actionLink)
            cell(healthDot)
            cell(healthLabel)
        }
    }

    /**
     * Load saved connection names into the list.
     */
    private fun loadSavedConnections() {
        connectionListModel.clear()
        settings.savedConnections.forEach { config ->
            connectionListModel.addElement(config.name)
        }
        // Ensure at least one entry so the list is never empty
        if (connectionListModel.isEmpty) {
            connectionListModel.addElement("new connection")
        }
        val idx = settings.lastConnectionIndex.coerceIn(0, connectionListModel.size - 1)
        connectionList.selectedIndex = idx
    }

    /**
     * Populate form fields from a saved connection config.
     */
    private fun populateFormFromConfig(config: SavedConnectionConfig) {
        hostField.text = config.host
        portField.text = config.port.toString()
        dbField.selectedIndex = config.db.coerceIn(0, 15)
        sslButton.isSelected = config.ssl
        usernameField.text = config.username
        passwordField.text = config.password
    }

    /**
     * Populate form from current settings.
     */
    private fun populateFormFromSettings() {
        val config = settings.current()
        populateFormFromConfig(config)
    }

    /**
     * Save the current form values as a named connection.
     */
    private fun handleSaveConnection() {
        val defaultName = "${hostField.text}:${portField.text}"
        val name = Messages.showInputDialog(
            project,
            message("valkey.browser.dialog.save.connection.prompt"),
            message("valkey.browser.dialog.save.connection.title"),
            null,
            defaultName,
            null,
            null
        ) ?: return

        val config = buildSavedConfigFromForm()
        config.name = name

        settings.saveCurrent(config)
        settings.selectConnection(name)
        loadSavedConnections()
    }

    /**
     * Delete the currently selected saved connection.
     */
    private fun handleDeleteConnection() {
        val selectedName = connectionList.selectedValue ?: return
        if (settings.savedConnections.size <= 1) {
            Messages.showErrorDialog(
                this,
                message("valkey.browser.dialog.delete.connection.cannot.last"),
                message("valkey.browser.dialog.delete.connection.title")
            )
            return
        }
        val ok = Messages.showYesNoDialog(
            this,
            message("valkey.browser.dialog.delete.connection.confirm", selectedName),
            message("valkey.browser.dialog.delete.connection.title"),
            Messages.getQuestionIcon()
        ) == Messages.YES
        if (!ok) return

        settings.removeConnection(selectedName)
        loadSavedConnections()
        populateFormFromSettings()
    }

    /**
     * Build a SavedConnectionConfig from current form values.
     */
    private fun buildSavedConfigFromForm(): SavedConnectionConfig {
        return SavedConnectionConfig(
            name = connectionList.selectedValue ?: "new connection",
            host = hostField.text,
            port = portField.text.toIntOrNull() ?: 6379,
            db = dbField.selectedIndex,
            ssl = sslButton.isSelected,
            username = usernameField.text,
            password = String(passwordField.password)
        )
    }

    private fun handleConnection() {
        if (service.isConnected) {
            logInfo("Disconnecting...")
            service.disconnect()
            updateUIForDisconnected()
            logInfo("Disconnected by user")
            return
        }

        val connection = ValkeyConnection(
            host = hostField.text,
            port = portField.text.toIntOrNull() ?: 6379,
            db = dbField.selectedIndex,
            ssl = sslButton.isSelected,
            username = usernameField.text,
            password = String(passwordField.password)
        )
        service.connection = connection
        logInfo("Connecting to ${connection.host}:${connection.port}/${connection.db} (ssl=${connection.ssl})...")

        updateUIForConnecting()

        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) { service.connect() }
            if (result.isFailure) {
                val e = result.exceptionOrNull()!!
                logError("Connection failed: ${e.javaClass.simpleName}: ${e.message}", e)
                invokeLater {
                    updateUIForDisconnected()
                    val msg = when (e) {
                        is JedisConnectionException -> message("valkey.browser.error.connection.cannot", e.message ?: "")
                        else -> message("valkey.browser.error.connection.auth", e.message ?: "")
                    }
                    Messages.showErrorDialog(
                        this@ValkeyBrowserPanel,
                        msg,
                        message("valkey.browser.error.connection.title")
                    )
                }
                return@launch
            }
            invokeLater {
                logInfo("Connected successfully, loading keys...")
                updateUIForConnected()
                loadKeys()
            }
        }
    }

    /**
     * Loads the selected key and renders its value by data type.
     */
    private fun loadSelectedKey() {
        val selected = keyList.selectedValue ?: return
        val selectedKey = selected.name
        coroutineScope.launch {
            try {
                val (_, rawValue) = withContext(Dispatchers.IO) {
                    val t = service.getType(selectedKey)
                    t to fetchValueByType(t, selectedKey)
                }
                invokeLater {
                    valueBorder.title = message("valkey.browser.section.value")
                    valueArea.text = rawValue
                }
            } catch (e: Exception) {
                invokeLater {
                    valueBorder.title = message("valkey.browser.section.value")
                    valueArea.text = message("valkey.browser.error.load.value", e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /**
     * Fetches the value for a key based on its Valkey type.
     */
    private fun fetchValueByType(type: String, key: String): String {
        return when (type) {
            "string" -> service.getString(key) ?: message("valkey.browser.nil")
            "list" -> formatList(service.getList(key))
            "hash" -> formatHash(service.getHash(key))
            "set" -> formatSet(service.getSet(key))
            "zset" -> formatZSet(service.getZSet(key))
            "stream" -> formatStream(service.getStream(key))
            else -> message("valkey.browser.nil")
        }
    }

    private fun formatList(items: List<String>): String {
        if (items.isEmpty()) return message("valkey.browser.empty")
        return items.mapIndexed { index, item ->
            message("valkey.browser.list.item", index, item)
        }.joinToString("\n")
    }

    private fun formatHash(entries: Map<String, String>): String {
        if (entries.isEmpty()) return message("valkey.browser.empty")
        return entries.map { (field, value) ->
            message("valkey.browser.hash.entry", field, value)
        }.joinToString("\n")
    }

    private fun formatSet(members: Set<String>): String {
        if (members.isEmpty()) return message("valkey.browser.empty")
        return members.sorted().joinToString(", ")
    }

    private fun formatZSet(entries: List<Pair<String, Double>>): String {
        if (entries.isEmpty()) return message("valkey.browser.empty")
        return entries.mapIndexed { index, (member, score) ->
            message("valkey.browser.zset.entry", index, member, score)
        }.joinToString("\n")
    }

    private fun formatStream(entries: List<Pair<String, Map<String, String>>>): String {
        if (entries.isEmpty()) return message("valkey.browser.empty")
        return entries.map { (id, fields) ->
            val fieldStr = fields.map { (k, v) -> "$k=$v" }.joinToString(", ")
            message("valkey.browser.stream.entry", id, fieldStr)
        }.joinToString("\n")
    }

    private fun updateUIForConnected() {
        actionLink.text = message("valkey.browser.action.disconnect")
        actionLink.isEnabled = true
        hostField.isEnabled = false
        portField.isEnabled = false
        dbField.isEnabled = false
        sslButton.isEnabled = false
        usernameField.isEnabled = false
        passwordField.isEnabled = false

        // Health indicator — green
        healthDot.background = successColor()
        healthLabel.text = message("valkey.browser.status.connected")
        healthLabel.foreground = successColor()
    }

    private fun updateUIForConnecting() {
        actionLink.text = message("valkey.browser.status.connecting")
        actionLink.isEnabled = false

        // Health indicator — orange
        healthDot.background = warningColor()
        healthLabel.text = message("valkey.browser.status.connecting")
        healthLabel.foreground = warningColor()
    }

    private fun updateUIForDisconnected() {
        actionLink.text = message("valkey.browser.action.connect")
        actionLink.isEnabled = true
        hostField.isEnabled = true
        portField.isEnabled = true
        dbField.isEnabled = true
        sslButton.isEnabled = true
        usernameField.isEnabled = true
        passwordField.isEnabled = true

        // Health indicator — muted
        healthDot.background = mutedForeground()
        healthLabel.text = message("valkey.browser.status.disconnected")
        healthLabel.foreground = mutedForeground()

        keyListModel.clear()
        valueArea.text = ""
        valueBorder.title = message("valkey.browser.section.value")
        statsLabel.text = ""

        // Hide loading indicator
        loadingIndicator.isVisible = false
        updateToolbarButtons()
    }


    /**
     * Scan keys from Valkey using pattern and limit from the toolbar fields.
     */
    private fun loadKeys() {
        if (!service.isConnected) {
            Messages.showErrorDialog(
                this,
                message("valkey.browser.error.not.connected"),
                message("valkey.browser.error.title")
            )
            return
        }
        val pattern = patternField.text.ifBlank { "*" }
        val count = limitField.text.toIntOrNull() ?: 100
        logInfo("loadKeys: pattern=$pattern, count=$count")

        invokeLater {
            loadingIndicator.isVisible = true
            loadingIndicator.revalidate()
        }
        coroutineScope.launch {
            try {
                logInfo("loadKeys: starting scan...")
                val result = withContext(Dispatchers.IO) {
                    logInfo("loadKeys: calling scanKeys...")
                    val loaded = service.scanKeys(pattern, count)
                    logInfo("loadKeys: scanKeys returned ${loaded.size} keys, fetching TTLs...")
                    // Fetch TTL for each key
                    val withTTL = loaded.map { name ->
                        val ttl = runCatching { service.getTTL(name) }.getOrNull() ?: -1L
                        KeyWithTTL(name, ttl)
                    }
                    logInfo("loadKeys: TTLs done, fetching DBSIZE...")
                    val size = runCatching { service.getDbSize() }.getOrNull() ?: -1L
                    logInfo("loadKeys: DBSIZE done, fetching memory info...")
                    val mem = runCatching { service.getUsedMemoryHuman() }.getOrNull() ?: "?"
                    logInfo("loadKeys: all done, dbSize=$size, memory=$mem")
                    withTTL to size to mem
                }
                val keys = result.first.first
                val dbSize = result.first.second
                val memory = result.second
                logInfo("loadKeys: updating UI with ${keys.size} keys")
                invokeLater {
                    loadingIndicator.isVisible = false
                    loadingIndicator.revalidate()
                    keyListModel.clear()
                    keys.forEach { keyListModel.addElement(it) }
                    cachedDbSize = dbSize
                    cachedMemory = memory
                    updateStats(keys.size, dbSize, memory)
                    updateEmptyState(keys.isEmpty())
                    if (keys.isEmpty()) {
                        valueArea.text = message("valkey.browser.empty.state.hint")
                        valueBorder.title = message("valkey.browser.section.value")
                    }
                }
            } catch (e: Exception) {
                logError("loadKeys: exception (${e.javaClass.simpleName}: ${e.message})", e)
                invokeLater {
                    Messages.showErrorDialog(
                        this@ValkeyBrowserPanel,
                        message("valkey.browser.error.load.keys", e.message ?: e.javaClass.simpleName),
                        message("valkey.browser.error.title")
                    )
                }
            }
        }
    }

    /**
     * Shows a dialog to create a new key.
     */
    private fun showCreateKeyDialog() {
        if (!service.isConnected) {
            Messages.showErrorDialog(
                this,
                message("valkey.browser.error.not.connected"),
                message("valkey.browser.error.title")
            )
            return
        }

        val dialog = JDialog().apply {
            title = message("valkey.browser.dialog.create.key.title")
            isModal = true
            val dialogRef = this

            val panel = JBPanel<Nothing>(java.awt.GridBagLayout()).apply {
                border = JBUI.Borders.empty(12)
                isOpaque = true
                background = panelBackground()

                val gbc = java.awt.GridBagConstraints().apply {
                    fill = java.awt.GridBagConstraints.HORIZONTAL
                    weightx = 1.0
                    insets = JBUI.insets(4)
                }

                // Key name
                gbc.gridy = 0
                add(JBLabel(message("valkey.browser.dialog.create.key.label.key")), gbc)
                gbc.gridy = 1
                val keyField = JBTextField()
                keyField.columns = 25
                add(keyField, gbc)

                // Value
                gbc.gridy = 2
                add(JBLabel(message("valkey.browser.dialog.create.key.label.value")), gbc)
                gbc.gridy = 3
                val dialogValueArea = JBTextArea(4, 25)
                dialogValueArea.lineWrap = true
                dialogValueArea.wrapStyleWord = true
                add(JBScrollPane(dialogValueArea), gbc)

                // TTL
                gbc.gridy = 4
                val ttlSecondsField = JBTextField("0").apply { columns = 6; isEnabled = false; toolTipText = message("valkey.browser.dialog.create.key.ttl.tooltip") }
                val ttlCheckbox = JBCheckBox(message("valkey.browser.dialog.create.key.label.ttl")).apply {
                    isSelected = false
                    addActionListener {
                        ttlSecondsField.isEnabled = isSelected
                    }
                }
                val ttlRow = JBPanel<Nothing>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply {
                    isOpaque = false
                    add(ttlCheckbox)
                    add(ttlSecondsField)
                }
                add(ttlRow, gbc)

                // OK/Cancel buttons
                gbc.gridy = 5
                gbc.weightx = 0.0
                gbc.anchor = java.awt.GridBagConstraints.EAST
                val btnPanel = JBPanel<Nothing>(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 4)).apply {
                    isOpaque = false
                    val okBtn = JButton(message("valkey.browser.dialog.create.key.ok")).apply {
                        addActionListener(object : ActionListener {
                            override fun actionPerformed(e: ActionEvent?) {
                                val key = keyField.text.trim()
                                val value = dialogValueArea.text
                                if (key.isBlank()) {
                                    Messages.showErrorDialog(dialogRef, message("valkey.browser.dialog.create.key.empty.key"), message("valkey.browser.error.title"))
                                    return
                                }
                                val ttlSeconds = if (ttlCheckbox?.isSelected == true) {
                                    try {
                                        val secs = ttlSecondsField.text.trim().toLong()
                                        if (secs <= 0) {
                                            Messages.showErrorDialog(dialogRef, message("valkey.browser.dialog.create.key.ttl.positive"), message("valkey.browser.error.title"))
                                            return
                                        }
                                        secs
                                    } catch (_: NumberFormatException) {
                                        Messages.showErrorDialog(dialogRef, message("valkey.browser.dialog.create.key.invalid.ttl"), message("valkey.browser.error.title"))
                                        return
                                    }
                                } else null
                                try {
                                    invokeLater {
                                        loadingIndicator.isVisible = true
                                        loadingIndicator.revalidate()
                                    }
                                    coroutineScope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { service.setStringWithTTL(key, value, ttlSeconds) }
                                            invokeLater {
                                                loadingIndicator.isVisible = false
                                                Messages.showInfoMessage(dialogRef, message("valkey.browser.dialog.create.key.success", key), "Success")
                                                loadKeys()
                                            }
                                    } catch (e: Exception) {
                                                invokeLater {
                                                    loadingIndicator.isVisible = false
                                                    Messages.showErrorDialog(
                                                        dialogRef,
                                                        message("valkey.browser.dialog.create.key.error", e.message ?: e.javaClass.simpleName),
                                                        message("valkey.browser.error.title")
                                                    )
                                                }
                                            }
                                        }
                                    } finally {
                                        dispose()
                                    }
                            }
                        })
                }
                    val cancelBtn = JButton(message("valkey.browser.dialog.create.key.cancel")).apply {
                        addActionListener { dispose() }
                    }
                    add(okBtn)
                    add(cancelBtn)
                }
                add(btnPanel, gbc)
            }

            contentPane = panel
            pack()
            setLocationRelativeTo(null)
        }
        dialog.show()
    }

    /**
     * Delete the selected key.
     */
    private fun handleDeleteKey() {
        val selected = keyList.selectedValue ?: run {
            Messages.showInputDialog(this, message("valkey.browser.dialog.delete.key.no.selection"), message("valkey.browser.error.title"), Messages.getWarningIcon())
            return
        }
        val ok = Messages.showYesNoDialog(
            this,
            message("valkey.browser.dialog.delete.key.message", selected.name),
            message("valkey.browser.dialog.delete.key.title"),
            Messages.getQuestionIcon()
        ) == Messages.YES
        if (!ok) return

        coroutineScope.launch {
            try {
                val pingOk = runCatching { withContext(Dispatchers.IO) { service.ping() } }.isSuccess
                if (!pingOk) {
                    invokeLater {
                        Messages.showErrorDialog(
                            this@ValkeyBrowserPanel,
                            message("valkey.browser.error.connection.lost"),
                            message("valkey.browser.error.connection.title")
                        )
                    }
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    service.deleteKey(selected.name)
                }
                invokeLater {
                    Messages.showInfoMessage(this@ValkeyBrowserPanel, message("valkey.browser.dialog.delete.key.success", selected.name), message("valkey.browser.dialog.delete.key.title"))
                    loadKeys()
                }
            } catch (e: Exception) {
                invokeLater {
                    Messages.showErrorDialog(this@ValkeyBrowserPanel, message("valkey.browser.dialog.delete.key.error", e.message ?: ""), message("valkey.browser.dialog.delete.key.title"))
                }
            }
        }
    }

    /**
     * Update the stats label with key count, DB size, and memory.
     */
    private fun updateStats(keyCount: Int, dbSize: Long, memory: String) {
        val sizeStr = if (dbSize >= 0) dbSize.toString() else "?"
        statsLabel.text = message("valkey.browser.label.stats", keyCount, sizeStr, memory)
    }

    /**
     * Shows/hides the empty state hint based on whether the key list is empty.
     */
    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            valueArea.text = message("valkey.browser.empty.state.hint")
            valueBorder.title = message("valkey.browser.section.value")
        }
    }

    /**
     * Refreshes context menu items so enabled/disabled state reflects current selection.
     */
    private fun updateContextMenu() {
        val hasSelection = keyList.selectedValue != null
        contextMenu.removeAll()
        contextMenu.add(createMenuItem(message("valkey.browser.action.refresh")) { loadKeys() })
        val deleteItem = createMenuItem(message("valkey.browser.action.delete.key")) { handleDeleteKey() }
        deleteItem.isEnabled = hasSelection
        contextMenu.add(deleteItem)
    }

    /**
     * Formats a TTL value for display as DD:HH:MM:SS.
     */
    private fun formatTTL(ttl: Long): String {
        return when {
            ttl == -1L -> message("valkey.browser.ttl.no.expiry")
            ttl == -2L -> "(gone)"
            ttl <= 0L -> "(expired)"
            else -> {
                val days = ttl / 86400
                val hours = (ttl % 86400) / 3600
                val minutes = (ttl % 3600) / 60
                val seconds = ttl % 60
                if (days > 0) {
                    "${days}d ${String.format("%02d:%02d:%02d", hours, minutes, seconds)}"
                } else {
                    String.format("%02d:%02d:%02d", hours, minutes, seconds)
                }
            }
        }
    }

    /**
     * Custom cell renderer that shows key name + TTL.
     */
    private inner class KeyCellRenderer : ListCellRenderer<KeyWithTTL> {
        private val typeDot = JBPanel<Nothing>().apply {
            preferredSize = Dimension(6, 6)
            background = accentColor()
            border = JBUI.Borders.emptyRight(8)
        }
        private val nameLabel = JBLabel()
        private val ttlLabel = JBLabel().apply {
            font = smallFont().deriveFont(Font.ITALIC)
            foreground = mutedForeground()
        }
        private val panel = JBPanel<Nothing>(java.awt.BorderLayout()).apply {
            isOpaque = true
            border = JBUI.Borders.empty(3, 8)
            // Left: dot + name in a row
            val left = JBPanel<Nothing>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2)).apply {
                isOpaque = false
                add(typeDot)
                add(nameLabel)
            }
            add(left, java.awt.BorderLayout.WEST)
            // Right: TTL pinned
            add(ttlLabel, java.awt.BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(list: javax.swing.JList<out KeyWithTTL>, value: KeyWithTTL?, index: Int, isSelected: Boolean, hasFocus: Boolean): java.awt.Component {
            if (value == null) {
                nameLabel.text = ""
                ttlLabel.text = ""
                typeDot.background = mutedForeground()
            } else {
                nameLabel.text = value.name
                ttlLabel.text = formatTTL(value.ttl)
                // Color the dot based on TTL (persistent = muted, expiring = accent)
                typeDot.background = if (value.ttl == -1L) mutedForeground() else accentColor()
            }
            if (isSelected) {
                panel.background = list.selectionBackground
                nameLabel.foreground = list.selectionForeground
                ttlLabel.foreground = list.selectionForeground
                typeDot.background = accentColor()
            } else {
                panel.background = list.background
                nameLabel.foreground = list.foreground
                ttlLabel.foreground = mutedForeground()
            }
            return panel
        }
    }
}
