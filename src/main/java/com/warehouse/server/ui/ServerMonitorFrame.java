package com.warehouse.server.ui;

import com.warehouse.common.network.NetworkAddress;
import com.warehouse.client.ui.AppButton;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.BasicComboBoxUI;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class ServerMonitorFrame extends JFrame {
    private static final Color BACKGROUND = new Color(245, 248, 251);
    private static final Color SURFACE = Color.WHITE;
    private static final Color INK = new Color(27, 47, 68);
    private static final Color MUTED = new Color(112, 132, 152);
    private static final Color BORDER = new Color(219, 229, 237);
    private static final Color TEAL = new Color(15, 139, 132);
    private static final Color GREEN = new Color(28, 133, 92);
    private static final Color RED = new Color(184, 69, 65);
    private static final Color INPUT_BORDER = new Color(207, 221, 230);
    @FunctionalInterface
    public interface Starter {
        RunningServer start(String localHost, String peerHost, int registryPort) throws Exception;
    }

    public interface RunningServer {
        boolean peerAvailable();
        void stop();
    }

    private final String serverId;
    private final int registryPort;
    private final int servicePort;
    private final Starter starter;
    private final JComboBox<String> localHost = new JComboBox<>();
    private final JTextField peerHost = new JTextField(18);
    private final JTextField registryPortField = new JTextField(6);
    private final JLabel databaseStatus = new JLabel("Chưa khởi động");
    private final JLabel peerStatus = new JLabel("Chưa khởi động");
    private final JLabel headerStatus = new JLabel("●  Chưa khởi động");
    private final JTextArea log = new JTextArea();
    private final JButton startButton = new AppButton("Khởi động", true);
    private final JButton stopButton = new AppButton("Dừng server", false);
    private final JButton refreshButton = new AppButton("Kiểm tra kết nối", false);
    private RunningServer runningServer;

    public ServerMonitorFrame(String serverId, String configuredHost, int registryPort,
                              int servicePort, String configuredPeerHost, Starter starter) {
        super("Quản lý Server - " + serverId);
        this.serverId = serverId;
        this.registryPort = registryPort;
        this.servicePort = servicePort;
        this.starter = starter;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setSize(860, 560);
        setMinimumSize(new Dimension(720, 500));
        setLocationRelativeTo(null);
        configureFields(configuredHost, configuredPeerHost);
        buildUi();
    }

    private void configureFields(String configuredHost, String configuredPeerHost) {
        List<String> addresses = NetworkAddress.localIpv4Addresses();
        for (String address : addresses) localHost.addItem(address);
        String selected = configuredHost == null || configuredHost.isBlank()
                || "auto".equalsIgnoreCase(configuredHost)
                ? addresses.isEmpty() ? "" : addresses.get(0) : configuredHost;
        localHost.setEditable(true);
        localHost.setSelectedItem(selected);
        peerHost.setText(configuredPeerHost == null ? "" : configuredPeerHost);
        registryPortField.setText(String.valueOf(registryPort));
    }

    private void buildUi() {
        getContentPane().setBackground(BACKGROUND);
        JPanel root = new JPanel(new BorderLayout(0, 22));
        root.setBackground(BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(26, 30, 26, 30));

        JPanel header = new JPanel(new BorderLayout(18, 0));
        header.setOpaque(false);
        JLabel title = new JLabel("Quản lý " + serverId);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        title.setForeground(INK);
        header.add(title, BorderLayout.WEST);
        headerStatus.setFont(headerStatus.getFont().deriveFont(Font.BOLD, 12f));
        headerStatus.setForeground(MUTED);
        JPanel badgeHolder = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 4));
        badgeHolder.setOpaque(false);
        badgeHolder.add(headerStatus);
        header.add(badgeHolder, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        styleHostSelector();
        styleInput(peerHost);
        styleInput(registryPortField);
        stopButton.setEnabled(false);
        stopButton.setVisible(false);
        refreshButton.setEnabled(false);
        refreshButton.addActionListener(e -> refreshPeer());
        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());

        JPanel configuration = new JPanel(new BorderLayout(0, 12));
        configuration.setOpaque(false);
        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        addField(fields, 0, "IP máy hiện tại", localHost, 0.34);
        addField(fields, 1, "IP server còn lại", peerHost, 0.46);
        addField(fields, 2, "Cổng RMI", registryPortField, 0.20);
        configuration.add(fields, BorderLayout.CENTER);
        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        JLabel ports = new JLabel("Dịch vụ " + servicePort + "  ·  Sync " + (servicePort + 1));
        ports.setFont(ports.getFont().deriveFont(12f));
        ports.setForeground(MUTED);
        actions.add(ports, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(refreshButton);
        buttons.add(startButton);
        buttons.add(stopButton);
        actions.add(buttons, BorderLayout.EAST);
        configuration.add(actions, BorderLayout.SOUTH);

        JPanel statuses = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        statuses.setOpaque(false);
        statuses.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, BORDER));
        statuses.add(statusItem("Cơ sở dữ liệu", databaseStatus));
        JLabel separator = new JLabel("   │   ");
        separator.setForeground(BORDER);
        statuses.add(separator);
        statuses.add(statusItem("Server còn lại", peerStatus));

        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setBackground(SURFACE);
        log.setForeground(INK);
        log.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        log.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
        JScrollPane logPane = new JScrollPane(log);
        logPane.setBorder(BorderFactory.createEmptyBorder());
        logPane.getViewport().setBackground(SURFACE);
        JPanel logCard = card();
        logCard.setLayout(new BorderLayout(0, 12));
        JLabel logTitle = new JLabel("Nhật ký hoạt động");
        logTitle.setFont(logTitle.getFont().deriveFont(Font.BOLD, 15f));
        logTitle.setForeground(INK);
        logCard.add(logTitle, BorderLayout.NORTH);
        logCard.add(logPane, BorderLayout.CENTER);

        JPanel content = new JPanel(new BorderLayout(0, 20));
        content.setOpaque(false);
        JPanel overview = new JPanel(new BorderLayout(0, 18));
        overview.setOpaque(false);
        overview.add(configuration, BorderLayout.NORTH);
        overview.add(statuses, BorderLayout.SOUTH);
        content.add(overview, BorderLayout.NORTH);
        content.add(logCard, BorderLayout.CENTER);
        root.add(content, BorderLayout.CENTER);
        add(root);
    }

    private static JPanel card() {
        JPanel card = new JPanel();
        card.setBackground(SURFACE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(18, 20, 18, 20)));
        return card;
    }

    private static void addField(JPanel panel, int column, String label, Component value, double weight) {
        JPanel field = new JPanel(new BorderLayout(0, 8));
        field.setOpaque(false);
        JLabel caption = new JLabel(label);
        caption.setFont(caption.getFont().deriveFont(Font.BOLD, 12f));
        caption.setForeground(MUTED);
        field.add(caption, BorderLayout.NORTH);
        field.add(value, BorderLayout.CENTER);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = column;
        c.gridy = 0;
        c.weightx = weight;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, column == 0 ? 0 : 7, 0, column == 2 ? 0 : 7);
        panel.add(field, c);
    }

    private void styleHostSelector() {
        localHost.setUI(new BasicComboBoxUI() {
            @Override protected JButton createArrowButton() {
                JButton arrow = new JButton() {
                    @Override protected void paintComponent(Graphics graphics) {
                        Graphics2D g = (Graphics2D) graphics.create();
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setColor(MUTED);
                        g.setStroke(new BasicStroke(1.5f));
                        int middle = getWidth() / 2;
                        int top = getHeight() / 2 - 2;
                        g.drawLine(middle - 4, top, middle, top + 4);
                        g.drawLine(middle, top + 4, middle + 4, top);
                        g.dispose();
                    }
                };
                arrow.setPreferredSize(new Dimension(28, 38));
                arrow.setBorder(BorderFactory.createEmptyBorder());
                arrow.setFocusPainted(false);
                arrow.setContentAreaFilled(false);
                return arrow;
            }
        });
        styleInput(localHost);
        Component editor = localHost.getEditor().getEditorComponent();
        if (editor instanceof JTextField textField) {
            textField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            textField.setForeground(INK);
            textField.setDisabledTextColor(INK);
            textField.setBorder(BorderFactory.createEmptyBorder(0, 11, 0, 0));
        }
    }

    private static void styleInput(JComponent input) {
        input.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        input.setBackground(SURFACE);
        input.setForeground(INK);
        input.setPreferredSize(new Dimension(input.getPreferredSize().width, 42));
        input.setBorder(new RoundedFieldBorder());
        if (input instanceof JTextField field) {
            field.setCaretColor(TEAL);
            field.setDisabledTextColor(INK);
        }
    }

    private static final class RoundedFieldBorder extends AbstractBorder {
        @Override public Insets getBorderInsets(Component component) {
            return new Insets(9, 12, 9, 12);
        }

        @Override public Insets getBorderInsets(Component component, Insets insets) {
            insets.set(9, 12, 9, 12);
            return insets;
        }

        @Override public void paintBorder(Component component, Graphics graphics,
                                          int x, int y, int width, int height) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(INPUT_BORDER);
            g.drawRoundRect(x, y, width - 1, height - 1, 10, 10);
            g.dispose();
        }
    }

    private JPanel statusItem(String title, JLabel value) {
        JPanel item = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 12));
        item.setOpaque(false);
        JLabel name = new JLabel(title + ":");
        name.setForeground(MUTED);
        item.add(name);
        value.setFont(value.getFont().deriveFont(Font.BOLD, 13f));
        value.setForeground(MUTED);
        item.add(value);
        return item;
    }

    private void startServer() {
        String local = String.valueOf(localHost.getSelectedItem()).trim();
        String peer = peerHost.getText().trim();
        if (!NetworkAddress.isIpv4(local)) {
            showMessage("IP máy hiện tại không phải IPv4 hợp lệ.");
            return;
        }
        if (!peer.isEmpty() && !NetworkAddress.isIpv4(peer)) {
            showMessage("IP Server còn lại không phải IPv4 hợp lệ.");
            return;
        }
        int selectedRegistryPort;
        try {
            selectedRegistryPort = Integer.parseInt(registryPortField.getText().trim());
        } catch (NumberFormatException ex) {
            showMessage("Cổng RMI Registry không hợp lệ.");
            return;
        }
        if (selectedRegistryPort < 1 || selectedRegistryPort > 65535) {
            showMessage("Cổng RMI Registry phải nằm trong khoảng 1-65535.");
            return;
        }
        setConfigurationEnabled(false);
        setStatus(databaseStatus, "Đang kết nối...", TEAL);
        setHeaderStatus("●  Đang khởi động", TEAL);
        appendLog("START", "Khởi động " + serverId + " tại " + local, "ĐANG CHỜ");
        new SwingWorker<RunningServer, Void>() {
            @Override protected RunningServer doInBackground() throws Exception {
                return starter.start(local, peer, selectedRegistryPort);
            }

            @Override protected void done() {
                try {
                    runningServer = get();
                    setStatus(databaseStatus, "Đã kết nối", GREEN);
                    setHeaderStatus("●  Đang chạy", GREEN);
                    setConfigurationEnabled(false);
                    refreshButton.setEnabled(true);
                    appendLog("START", "Đã đăng ký các dịch vụ RMI", "OK");
                    refreshPeer();
                } catch (Exception ex) {
                    setConfigurationEnabled(true);
                    setStatus(databaseStatus, "Chưa kết nối", MUTED);
                    setHeaderStatus("●  Khởi động thất bại", RED);
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    appendLog("START", cause.getMessage(), "LỖI");
                    showMessage("Không thể khởi động Server: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void refreshPeer() {
        RunningServer server = runningServer;
        if (server == null) {
            setStatus(peerStatus, "Chưa khởi động", MUTED);
            return;
        }
        setStatus(peerStatus, "Đang kiểm tra...", TEAL);
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() {
                return server.peerAvailable();
            }

            @Override protected void done() {
                try {
                    boolean connected = get();
                    if (runningServer != server) return;
                    setStatus(peerStatus, connected ? "Đã kết nối" : "Chưa kết nối",
                            connected ? GREEN : RED);
                    appendLog("PEER", connected ? "Server còn lại đã phản hồi" : "Server còn lại chưa online",
                            connected ? "OK" : "CHỜ");
                } catch (Exception ex) {
                    if (runningServer == server) setStatus(peerStatus, "Không kiểm tra được", RED);
                }
            }
        }.execute();
    }

    private void stopServer() {
        RunningServer server = runningServer;
        if (server == null) {
            dispose();
            return;
        }
        startButton.setEnabled(false);
        stopButton.setEnabled(false);
        refreshButton.setEnabled(false);
        setHeaderStatus("●  Đang dừng", TEAL);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                server.stop();
                return null;
            }

            @Override protected void done() {
                runningServer = null;
                setStatus(databaseStatus, "Đã dừng", MUTED);
                setStatus(peerStatus, "Đã dừng", MUTED);
                setHeaderStatus("●  Đã dừng", MUTED);
                appendLog("STOP", "Đã giải phóng tài nguyên Server", "OK");
                setConfigurationEnabled(true);
            }
        }.execute();
    }

    private void setConfigurationEnabled(boolean enabled) {
        localHost.setEnabled(enabled);
        peerHost.setEnabled(enabled);
        registryPortField.setEnabled(enabled);
        startButton.setEnabled(enabled);
        startButton.setVisible(runningServer == null);
        stopButton.setVisible(runningServer != null);
        stopButton.setEnabled(!enabled && runningServer != null);
    }

    private static void setStatus(JLabel label, String text, Color color) {
        label.setText(text);
        label.setForeground(color);
    }

    private void setHeaderStatus(String text, Color color) {
        setStatus(headerStatus, text, color);
    }

    private void showMessage(String message) {
        JOptionPane.showMessageDialog(this, message, "Cấu hình Server", JOptionPane.WARNING_MESSAGE);
    }

    private void appendLog(String component, String event, String result) {
        log.append(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                + "   " + event + ("LỖI".equals(result) ? " (Lỗi)" : "")
                + System.lineSeparator());
    }
}
