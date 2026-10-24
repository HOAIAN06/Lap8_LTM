package com.warehouse.client.ui;

import com.warehouse.client.rmi.WarehouseClient;
import com.warehouse.common.dto.LoginResult;

import javax.swing.*;
import java.awt.*;

public final class LoginFrame extends JFrame {
    private final WarehouseClient client;
    private final String clientId;
    private final JTextField username = new JTextField(18);
    private final JTextField password = new JTextField(18);
    private final JLabel status = new JLabel("Nhập tài khoản và mật khẩu của nhân viên.");
    private final JButton login = new JButton("Đăng nhập");

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
        status.setForeground(new Color(90, 100, 110));
        form.add(status, c);
        login.addActionListener(e -> authenticate());
        username.addActionListener(e -> authenticate());
        password.addActionListener(e -> authenticate());
        getRootPane().setDefaultButton(login);
        add(form);
        pack();
        setLocationRelativeTo(null);
    }

    private void authenticate() {
        String user = username.getText().trim();
        String pass = password.getText();
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
}
