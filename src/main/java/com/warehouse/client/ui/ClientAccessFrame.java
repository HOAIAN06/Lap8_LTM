package com.warehouse.client.ui;

import com.warehouse.client.config.ClientConfig;
import com.warehouse.client.rmi.WarehouseClient;
import com.warehouse.common.dto.LoginResult;
import com.warehouse.common.dto.RegistrationResult;
import com.warehouse.common.network.NetworkAddress;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ClientAccessFrame extends JFrame {
    private static final Color BACKGROUND = new Color(245, 248, 251);
    private static final Color SIDE = new Color(249, 251, 252);
    private static final Color WHITE = Color.WHITE;
    private static final Color INK = new Color(27, 47, 68);
    private static final Color MUTED = new Color(112, 132, 152);
    private static final Color BORDER = new Color(211, 223, 232);
    private static final Color TEAL = new Color(15, 139, 132);
    private static final Color GREEN = new Color(28, 133, 92);
    private static final Color RED = new Color(184, 69, 65);

    private final ClientConfig config;
    private final Path configPath;
    private final JTextField host = new JTextField();
    private final JTextField port = new JTextField();
    private final JLabel connectionStatus = new JLabel("Chưa kết nối server");
    private final JButton connectButton = new AppButton("Kết nối server", true);
    private final JButton loginTab = new JButton("Đăng nhập");
    private final JButton registerTab = new JButton("Đăng ký");
    private final CardLayout accountCards = new CardLayout();
    private final JPanel accountForms = new JPanel(accountCards);
    private final JTextField loginUsername = new JTextField();
    private final JPasswordField loginPassword = new JPasswordField();
    private final JButton loginButton = new AppButton("Đăng nhập", true);
    private final JTextField fullName = new JTextField();
    private final JTextField registerUsername = new JTextField();
    private final JTextField registerPassword = new JTextField();
    private final JButton registerButton = new AppButton("Tạo tài khoản", true);
    private final JLabel accountStatus = new JLabel("Kết nối server để tiếp tục.");
    private WarehouseClient client;
    private boolean registering;

    public ClientAccessFrame(ClientConfig config, Path configPath) {
        super("Client " + config.clientId() + " - Distributed Warehouse");
        this.config = config;
        this.configPath = configPath;
        host.setText(config.serverHost());
        port.setText(String.valueOf(config.registryPort()));
        loginUsername.setText(config.username());
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(940, 660));
        buildUi();
        setSize(1000, 680);
        setLocationRelativeTo(null);
    }

    private void buildUi() {
        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(30, 30, 30, 30));
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(WHITE);
        card.setBorder(BorderFactory.createLineBorder(BORDER));
        card.setPreferredSize(new Dimension(850, 550));

        JPanel serverSide = new JPanel(new BorderLayout(0, 22));
        serverSide.setBackground(SIDE);
        serverSide.setPreferredSize(new Dimension(335, 550));
        serverSide.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, BORDER),
                BorderFactory.createEmptyBorder(38, 36, 34, 36)));
        JLabel title = new JLabel("Kết nối server");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 23f));
        title.setForeground(INK);
        JLabel subtitle = new JLabel("Client " + config.clientId());
        subtitle.setForeground(MUTED);
        JPanel heading = new JPanel(new GridLayout(2, 1, 0, 5));
        heading.setOpaque(false);
        heading.add(title);
        heading.add(subtitle);
        serverSide.add(heading, BorderLayout.NORTH);

        styleInput(host);
        styleInput(port);
        JPanel connectionForm = new JPanel();
        connectionForm.setOpaque(false);
        connectionForm.setLayout(new BoxLayout(connectionForm, BoxLayout.Y_AXIS));
        connectionForm.add(field("IP server", host));
        connectionForm.add(Box.createVerticalStrut(16));
        connectionForm.add(field("Cổng RMI", port));
        connectionForm.add(Box.createVerticalStrut(22));
        connectButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        connectButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        connectionForm.add(connectButton);
        connectionForm.add(Box.createVerticalStrut(15));
        connectionStatus.setForeground(MUTED);
        connectionStatus.setFont(connectionStatus.getFont().deriveFont(12f));
        connectionStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        connectionForm.add(connectionStatus);
        serverSide.add(connectionForm, BorderLayout.CENTER);
        card.add(serverSide, BorderLayout.WEST);

        JPanel accountSide = new JPanel(new BorderLayout(0, 22));
        accountSide.setBackground(WHITE);
        accountSide.setBorder(BorderFactory.createEmptyBorder(38, 42, 30, 42));
        JPanel tabs = new JPanel(new GridLayout(1, 2, 5, 0));
        tabs.setBackground(BACKGROUND);
        tabs.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        tabs.add(loginTab);
        tabs.add(registerTab);
        styleTab(loginTab, true);
        styleTab(registerTab, false);
        accountSide.add(tabs, BorderLayout.NORTH);

        accountForms.setOpaque(false);
        accountForms.add(loginForm(), "login");
        accountForms.add(registerForm(), "register");
        accountSide.add(accountForms, BorderLayout.CENTER);
        accountStatus.setForeground(MUTED);
        accountStatus.setFont(accountStatus.getFont().deriveFont(12f));
        accountSide.add(accountStatus, BorderLayout.SOUTH);
        card.add(accountSide, BorderLayout.CENTER);
        root.add(card);
        add(root);

        connectButton.addActionListener(e -> connect());
        loginTab.addActionListener(e -> switchAccountForm(false));
        registerTab.addActionListener(e -> switchAccountForm(true));
        loginButton.addActionListener(e -> authenticate());
        registerButton.addActionListener(e -> registerAccount());
        loginPassword.addActionListener(e -> authenticate());
        registerPassword.addActionListener(e -> registerAccount());
        host.getDocument().addDocumentListener(onConnectionEdited());
        port.getDocument().addDocumentListener(onConnectionEdited());
        getRootPane().setDefaultButton(connectButton);
    }

    private JPanel loginForm() {
        styleInput(loginUsername);
        styleInput(loginPassword);
        JPanel form = formPanel();
        form.add(field("Tài khoản", loginUsername));
        form.add(Box.createVerticalStrut(17));
        form.add(field("Mật khẩu", loginPassword));
        form.add(Box.createVerticalStrut(24));
        addFullWidthButton(form, loginButton);
        return form;
    }

    private JPanel registerForm() {
        for (JComponent input : new JComponent[]{fullName, registerUsername,
                registerPassword}) styleInput(input);
        JPanel form = formPanel();
        form.add(field("Họ và tên", fullName));
        form.add(Box.createVerticalStrut(10));
        form.add(field("Tài khoản", registerUsername));
        form.add(Box.createVerticalStrut(10));
        form.add(field("Mật khẩu", registerPassword));
        form.add(Box.createVerticalStrut(16));
        addFullWidthButton(form, registerButton);
        return form;
    }

    private static JPanel formPanel() {
        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        return form;
    }

    private static JPanel field(String label, JComponent input) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel caption = new JLabel(label);
        caption.setFont(caption.getFont().deriveFont(Font.BOLD, 12f));
        caption.setForeground(INK);
        panel.add(caption, BorderLayout.NORTH);
        panel.add(input, BorderLayout.CENTER);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 68));
        return panel;
    }

    private static void addFullWidthButton(JPanel form, JButton button) {
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        form.add(button);
    }

    private static void styleInput(JComponent input) {
        input.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        input.setBackground(WHITE);
        input.setForeground(INK);
        input.setPreferredSize(new Dimension(100, 44));
        input.setBorder(new RoundedBorder());
        if (input instanceof JTextField field) field.setCaretColor(TEAL);
    }

    private static void setActionEnabled(JButton button, boolean enabled) {
        button.setEnabled(enabled);
    }

    private static void styleTab(JButton button, boolean selected) {
        button.setFont(button.getFont().deriveFont(Font.BOLD, 13f));
        button.setForeground(selected ? TEAL : MUTED);
        button.setBackground(selected ? WHITE : BACKGROUND);
        button.setBorder(BorderFactory.createEmptyBorder(11, 15, 11, 15));
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private void switchAccountForm(boolean showRegister) {
        registering = showRegister;
        accountCards.show(accountForms, showRegister ? "register" : "login");
        styleTab(loginTab, !showRegister);
        styleTab(registerTab, showRegister);
        accountStatus.setText(client == null ? "Kết nối server để tiếp tục." : "Đã kết nối. Sẵn sàng tiếp tục.");
        accountStatus.setForeground(client == null ? MUTED : GREEN);
        getRootPane().setDefaultButton(client == null ? connectButton
                : showRegister ? registerButton : loginButton);
    }

    private DocumentListener onConnectionEdited() {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { disconnect(); }
            @Override public void removeUpdate(DocumentEvent event) { disconnect(); }
            @Override public void changedUpdate(DocumentEvent event) { disconnect(); }
        };
    }

    private void disconnect() {
        if (client == null) return;
        client = null;
        setActionEnabled(loginButton, true);
        setActionEnabled(registerButton, true);
        connectionStatus.setText("Địa chỉ đã thay đổi. Kết nối lại.");
        connectionStatus.setForeground(MUTED);
        accountStatus.setText("Kết nối server để tiếp tục.");
        accountStatus.setForeground(MUTED);
        getRootPane().setDefaultButton(connectButton);
    }

    private void connect() {
        String address = host.getText().trim();
        if (!NetworkAddress.isIpv4(address)) {
            showConnectionError("IP server phải là IPv4 hợp lệ.");
            return;
        }
        int registryPort;
        try { registryPort = Integer.parseInt(port.getText().trim()); }
        catch (NumberFormatException ex) {
            showConnectionError("Cổng RMI không hợp lệ.");
            return;
        }
        if (registryPort < 1 || registryPort > 65535) {
            showConnectionError("Cổng RMI phải từ 1 đến 65535.");
            return;
        }
        client = null;
        setActionEnabled(loginButton, false);
        setActionEnabled(registerButton, false);
        setActionEnabled(connectButton, false);
        connectionStatus.setText("Đang kết nối...");
        connectionStatus.setForeground(MUTED);
        accountStatus.setText("Đang kết nối server...");
        accountStatus.setForeground(MUTED);
        new SwingWorker<WarehouseClient, Void>() {
            @Override protected WarehouseClient doInBackground() throws Exception {
                return WarehouseClient.connect(address, registryPort);
            }

            @Override protected void done() {
                setActionEnabled(connectButton, true);
                setActionEnabled(loginButton, true);
                setActionEnabled(registerButton, true);
                try {
                    WarehouseClient connected = get();
                    if (!address.equals(host.getText().trim())
                            || !String.valueOf(registryPort).equals(port.getText().trim())) {
                        connectionStatus.setText("Địa chỉ đã thay đổi. Kết nối lại.");
                        accountStatus.setText("Kết nối server để tiếp tục.");
                        return;
                    }
                    client = connected;
                    connectionStatus.setText("●  Đã kết nối");
                    connectionStatus.setForeground(GREEN);
                    accountStatus.setText("Đã kết nối. Sẵn sàng tiếp tục.");
                    accountStatus.setForeground(GREEN);
                    getRootPane().setDefaultButton(registering ? registerButton : loginButton);
                    saveLastConnection(address, registryPort);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    showConnectionError("Không thể kết nối: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void showConnectionError(String message) {
        connectionStatus.setText(message);
        connectionStatus.setForeground(RED);
        accountStatus.setText("Kết nối server để tiếp tục.");
        accountStatus.setForeground(MUTED);
    }

    private void saveLastConnection(String address, int registryPort) {
        try {
            Properties properties = new Properties();
            if (Files.exists(configPath)) {
                try (var input = Files.newInputStream(configPath)) { properties.load(input); }
            }
            properties.setProperty("server.host", address);
            properties.setProperty("rmi.registry.port", String.valueOf(registryPort));
            properties.setProperty("client.id", config.clientId());
            NetworkAddress.saveProperties(configPath, properties);
        } catch (IOException ex) {
            connectionStatus.setText("Đã kết nối (chưa lưu được địa chỉ).");
        }
    }

    private void authenticate() {
        if (client == null) {
            setAccountError("Kết nối server trước khi đăng nhập.");
            return;
        }
        String user = loginUsername.getText().trim();
        String pass = new String(loginPassword.getPassword());
        if (user.isEmpty() || pass.isEmpty()) {
            setAccountError("Vui lòng nhập tài khoản và mật khẩu.");
            return;
        }
        WarehouseClient connected = client;
        setActionEnabled(loginButton, false);
        accountStatus.setText("Đang đăng nhập...");
        accountStatus.setForeground(MUTED);
        new SwingWorker<LoginResult, Void>() {
            @Override protected LoginResult doInBackground() throws Exception {
                return connected.warehouse().login(user, pass);
            }

            @Override protected void done() {
                setActionEnabled(loginButton, true);
                try {
                    LoginResult result = get();
                    if (client != connected) return;
                    if (!result.authenticated()) {
                        setAccountError(result.message());
                        return;
                    }
                    dispose();
                    new DashboardFrame(connected, config.clientId(), result.username()).setVisible(true);
                } catch (Exception ex) {
                    setAccountError("Không thể đăng nhập: " + errorMessage(ex));
                }
            }
        }.execute();
    }

    private void registerAccount() {
        if (client == null) {
            setAccountError("Kết nối server trước khi đăng ký.");
            return;
        }
        String name = fullName.getText().trim();
        String user = registerUsername.getText().trim();
        String pass = registerPassword.getText();
        if (name.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            setAccountError("Vui lòng nhập đầy đủ thông tin đăng ký.");
            return;
        }
        WarehouseClient connected = client;
        setActionEnabled(registerButton, false);
        accountStatus.setText("Đang tạo tài khoản...");
        accountStatus.setForeground(MUTED);
        new SwingWorker<RegistrationResult, Void>() {
            @Override protected RegistrationResult doInBackground() throws Exception {
                return connected.warehouse().register(user, pass, name);
            }

            @Override protected void done() {
                setActionEnabled(registerButton, true);
                try {
                    RegistrationResult result = get();
                    if (client != connected) return;
                    if (!result.registered()) {
                        setAccountError(result.message());
                        return;
                    }
                    loginUsername.setText(result.username());
                    loginPassword.setText("");
                    registerPassword.setText("");
                    switchAccountForm(false);
                    accountStatus.setText("Đăng ký thành công. Đăng nhập để tiếp tục.");
                    accountStatus.setForeground(GREEN);
                } catch (Exception ex) {
                    setAccountError("Không thể đăng ký: " + errorMessage(ex));
                }
            }
        }.execute();
    }

    private void setAccountError(String message) {
        accountStatus.setText(message);
        accountStatus.setForeground(RED);
    }

    private static String errorMessage(Exception ex) {
        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
        return cause.getMessage();
    }

    private static final class RoundedBorder extends AbstractBorder {
        @Override public Insets getBorderInsets(Component component) {
            return new Insets(10, 13, 10, 13);
        }

        @Override public Insets getBorderInsets(Component component, Insets insets) {
            insets.set(10, 13, 10, 13);
            return insets;
        }

        @Override public void paintBorder(Component component, Graphics graphics,
                                          int x, int y, int width, int height) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BORDER);
            g.drawRoundRect(x, y, width - 1, height - 1, 10, 10);
            g.dispose();
        }
    }
}
