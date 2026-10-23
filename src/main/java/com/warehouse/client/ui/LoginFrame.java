package com.warehouse.client.ui;

import com.warehouse.client.rmi.WarehouseClient;
import com.warehouse.common.dto.LoginResult;

import javax.swing.*;
import java.awt.*;

public final class LoginFrame extends JFrame {
    private final WarehouseClient client;
    private final String clientId;
    private final JTextField username = new JTextField(18);
    private final JPasswordField password = new JPasswordField(18);

    public LoginFrame(WarehouseClient client, String clientId, String defaultUsername) {
        super("Đăng nhập - Distributed Warehouse");
        this.client = client;
        this.clientId = clientId;
        username.setText(defaultUsername);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.anchor = GridBagConstraints.LINE_END;
        form.add(new JLabel("Tài khoản:"), c);
        c.gridx = 1; c.anchor = GridBagConstraints.LINE_START; form.add(username, c);
        c.gridx = 0; c.gridy = 1; c.anchor = GridBagConstraints.LINE_END; form.add(new JLabel("Mật khẩu:"), c);
        c.gridx = 1; c.anchor = GridBagConstraints.LINE_START; form.add(password, c);
        JButton login = new JButton("Đăng nhập");
        c.gridy = 2; c.anchor = GridBagConstraints.CENTER; form.add(login, c);
        login.addActionListener(e -> authenticate());
        add(form);
        pack();
        setLocationRelativeTo(null);
    }

    private void authenticate() {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<LoginResult, Void>() {
            @Override protected LoginResult doInBackground() throws Exception {
                return client.warehouse().login(username.getText(), new String(password.getPassword()));
            }
            @Override protected void done() {
                setCursor(Cursor.getDefaultCursor());
                try {
                    LoginResult result = get();
                    if (!result.authenticated()) {
                        JOptionPane.showMessageDialog(LoginFrame.this, result.message(), "Đăng nhập", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    dispose();
                    new DashboardFrame(client, clientId, result.username()).setVisible(true);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(LoginFrame.this, ex.getMessage(), "Lỗi kết nối", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
}
