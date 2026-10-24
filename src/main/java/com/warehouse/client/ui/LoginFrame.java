package com.warehouse.client.ui;

import com.warehouse.client.rmi.WarehouseClient;
import com.warehouse.common.dto.LoginResult;
import com.warehouse.common.dto.RegistrationResult;

import javax.swing.*;
import java.awt.*;

public final class LoginFrame extends JFrame {
    private final WarehouseClient client;
    private final String clientId;
    private final JTextField username = new JTextField(18);
    private final JPasswordField password = new JPasswordField(18);
    private final JLabel status = new JLabel("Nhập tài khoản và mật khẩu của nhân viên.");
    private final JButton login = new JButton("Đăng nhập");
    private final JButton register = new JButton("Đăng ký tài khoản");

    public LoginFrame(WarehouseClient client, String clientId, String defaultUsername) {
        super("Đăng nhập - Distributed Warehouse");
        this.client = client;
        this.clientId = clientId;
        username.setText(defaultUsername);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(460, 290));
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.anchor = GridBagConstraints.LINE_END;
        JLabel title = new JLabel("Đăng nhập hệ thống kho");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 21f));
        c.gridwidth = 2; c.anchor = GridBagConstraints.CENTER;
        form.add(title, c);
        c.gridy = 1;
        form.add(new JLabel("Client: " + clientId), c);
        c.gridwidth = 1; c.gridy = 2; c.anchor = GridBagConstraints.LINE_END;
        form.add(new JLabel("Username:"), c);
        c.gridx = 1; c.anchor = GridBagConstraints.LINE_START; form.add(username, c);
        c.gridx = 0; c.gridy = 3; c.anchor = GridBagConstraints.LINE_END;
        form.add(new JLabel("Mật khẩu:"), c);
        c.gridx = 1; c.anchor = GridBagConstraints.LINE_START; form.add(password, c);
        c.gridx = 0; c.gridy = 4; c.gridwidth = 2; c.anchor = GridBagConstraints.CENTER;
        login.setPreferredSize(new Dimension(150, 36));
        form.add(login, c);
        c.gridy = 5;
        register.setPreferredSize(new Dimension(180, 32));
        form.add(register, c);
        c.gridy = 6;
        status.setForeground(new Color(90, 100, 110));
        form.add(status, c);
        login.addActionListener(e -> authenticate());
        register.addActionListener(e -> registerAccount());
        username.addActionListener(e -> authenticate());
        password.addActionListener(e -> authenticate());
        getRootPane().setDefaultButton(login);
        add(form);
        pack();
        setLocationRelativeTo(null);
    }

    private void authenticate() {
        String user = username.getText().trim();
        String pass = new String(password.getPassword());
        if (user.isEmpty() || pass.isEmpty()) {
            status.setText("Vui lòng nhập username và mật khẩu.");
            return;
        }
        login.setEnabled(false);
        status.setText("Đang kiểm tra nhân viên trên Server...");
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<LoginResult, Void>() {
            @Override protected LoginResult doInBackground() throws Exception {
                return client.warehouse().login(user, pass);
            }
            @Override protected void done() {
                login.setEnabled(true);
                setCursor(Cursor.getDefaultCursor());
                try {
                    LoginResult result = get();
                    if (!result.authenticated()) {
                        status.setText(result.message());
                        return;
                    }
                    dispose();
                    new DashboardFrame(client, clientId, result.username()).setVisible(true);
                } catch (Exception ex) {
                    status.setText("Không thể đăng nhập: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void registerAccount() {
        JTextField name = new JTextField(18);
        JTextField user = new JTextField(username.getText().trim(), 18);
        JPasswordField pass = new JPasswordField(18);
        JPasswordField confirm = new JPasswordField(18);
        JPanel fields = new JPanel(new GridLayout(0, 2, 8, 8));
        fields.add(new JLabel("Họ và tên:")); fields.add(name);
        fields.add(new JLabel("Username:")); fields.add(user);
        fields.add(new JLabel("Mật khẩu:")); fields.add(pass);
        fields.add(new JLabel("Nhập lại mật khẩu:")); fields.add(confirm);
        if (JOptionPane.showConfirmDialog(this, fields, "Đăng ký tài khoản",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        String fullName = name.getText().trim();
        String selectedUsername = user.getText().trim();
        String selectedPassword = new String(pass.getPassword());
        if (!selectedPassword.equals(new String(confirm.getPassword()))) {
            status.setText("Mật khẩu nhập lại không khớp.");
            return;
        }
        register.setEnabled(false);
        login.setEnabled(false);
        status.setText("Đang đăng ký tài khoản...");
        new SwingWorker<RegistrationResult, Void>() {
            @Override protected RegistrationResult doInBackground() throws Exception {
                return client.warehouse().register(selectedUsername, selectedPassword, fullName);
            }

            @Override protected void done() {
                register.setEnabled(true);
                login.setEnabled(true);
                try {
                    RegistrationResult result = get();
                    status.setText(result.message());
                    if (result.registered()) {
                        username.setText(result.username());
                        password.setText("");
                    }
                } catch (Exception ex) {
                    status.setText("Không thể đăng ký: " + ex.getMessage());
                }
            }
        }.execute();
    }
}
