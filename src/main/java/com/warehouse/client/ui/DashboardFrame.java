package com.warehouse.client.ui;

import com.warehouse.client.rmi.WarehouseClient;
import com.warehouse.common.dto.LockLease;
import com.warehouse.common.dto.ProductInput;
import com.warehouse.common.model.Product;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.UUID;

public final class DashboardFrame extends JFrame {
    private final WarehouseClient client;
    private final String clientId;
    private final String username;
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Mã SP", "Tên", "Danh mục", "Giá", "Tồn kho", "Khóa"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private List<Product> products = List.of();
    private LockLease currentLease;

    public DashboardFrame(WarehouseClient client, String clientId, String username) {
        super("Warehouse Dashboard - " + username);
        this.client = client; this.clientId = clientId; this.username = username;
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(900, 520); setLocationRelativeTo(null);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refresh = new JButton("Làm mới");
        JButton add = new JButton("Thêm");
        JButton edit = new JButton("Sửa");
        JButton importStock = new JButton("Nhập kho");
        JButton exportStock = new JButton("Xuất kho");
        JButton history = new JButton("Lịch sử");
        JButton lock = new JButton("Bắt đầu chỉnh sửa");
        JButton release = new JButton("Giải phóng khóa");
        actions.add(refresh); actions.add(add); actions.add(edit); actions.add(importStock);
        actions.add(exportStock); actions.add(history); actions.add(lock); actions.add(release);
        refresh.addActionListener(e -> loadProducts());
        add.addActionListener(e -> createProduct());
        edit.addActionListener(e -> editProduct());
        importStock.addActionListener(e -> changeInventory(true));
        exportStock.addActionListener(e -> changeInventory(false));
        history.addActionListener(e -> showHistory());
        lock.addActionListener(e -> acquireLock());
        release.addActionListener(e -> releaseLock());
        add(actions, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        loadProducts();
    }

    private void loadProducts() {
        new SwingWorker<List<Product>, Void>() {
            @Override protected List<Product> doInBackground() throws Exception {
                return client.warehouse().listProducts("");
            }
            @Override protected void done() {
                try {
                    products = get(); model.setRowCount(0);
                    for (Product p : products) model.addRow(new Object[]{p.productCode(), p.productName(),
                            p.category(), p.price(), p.quantity(), "Không"});
                } catch (Exception ex) { showError(ex); }
            }
        }.execute();
    }

    private void acquireLock() {
        Product product = selectedProduct();
        if (product == null) return;
        new SwingWorker<LockLease, Void>() {
            @Override protected LockLease doInBackground() throws Exception {
                return client.locks().acquire(product.id(), clientId);
            }
            @Override protected void done() {
                try { currentLease = get(); JOptionPane.showMessageDialog(DashboardFrame.this,
                        "Đã khóa " + product.productCode() + " đến " + currentLease.expiresAt()); }
                catch (Exception ex) { showError(ex); }
            }
        }.execute();
    }

    private void releaseLock() {
        if (currentLease == null) return;
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                client.locks().release(currentLease.lockToken(), clientId); return null;
            }

            @Override protected void done() {
                try { get(); currentLease = null; } catch (Exception ex) { showError(ex); }
            }
        }.execute();
    }

    private void createProduct() {
        ProductInput input = productInput("Thêm sản phẩm", null);
        if (input == null) return;
        new SwingWorker<Product, Void>() {
            @Override protected Product doInBackground() throws Exception {
                return client.warehouse().createProduct(input, clientId, username);
            }
            @Override protected void done() { try { get(); loadProducts(); } catch (Exception ex) { showError(ex); } }
        }.execute();
    }

    private void editProduct() {
        Product product = selectedProduct();
        if (product == null || currentLease == null || !currentLease.resourceId().equals(product.id())) {
            JOptionPane.showMessageDialog(this, "Hãy khóa sản phẩm trước khi sửa."); return;
        }
        ProductInput input = productInput("Sửa sản phẩm", product);
        if (input == null) return;
        new SwingWorker<Product, Void>() {
            @Override protected Product doInBackground() throws Exception {
                return client.warehouse().updateProduct(product.id(), input, currentLease.lockToken(),
                        currentLease.fencingToken(), clientId, username);
            }
            @Override protected void done() { try { get(); loadProducts(); } catch (Exception ex) { showError(ex); } }
        }.execute();
    }

    private void changeInventory(boolean importing) {
        Product product = selectedProduct();
        if (product == null || currentLease == null || !currentLease.resourceId().equals(product.id())) {
            JOptionPane.showMessageDialog(this, "Hãy khóa sản phẩm trước khi nhập/xuất."); return;
        }
        String text = JOptionPane.showInputDialog(this, "Số lượng:");
        if (text == null) return;
        try {
            int amount = Integer.parseInt(text);
            new SwingWorker<Object, Void>() {
                @Override protected Object doInBackground() throws Exception {
                    return client.warehouse().changeInventory(product.id(), amount, importing,
                            currentLease.lockToken(), currentLease.fencingToken(), clientId, username);
                }
                @Override protected void done() { try { get(); loadProducts(); } catch (Exception ex) { showError(ex); } }
            }.execute();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Số lượng phải là số nguyên dương.");
        }
    }

    private void showHistory() {
        Product product = selectedProduct();
        if (product == null) return;
        new SwingWorker<List<com.warehouse.common.model.InventoryTransaction>, Void>() {
            @Override protected List<com.warehouse.common.model.InventoryTransaction> doInBackground() throws Exception {
                return client.warehouse().transactionHistory(product.id());
            }
            @Override protected void done() {
                try {
                    StringBuilder text = new StringBuilder();
                    for (var tx : get()) text.append(tx.transactionType()).append(" ")
                            .append(tx.quantity()).append(" -> ").append(tx.quantityAfter()).append("\n");
                    JOptionPane.showMessageDialog(DashboardFrame.this,
                            text.length() == 0 ? "Chưa có giao dịch." : text, "Lịch sử " + product.productCode(),
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) { showError(ex); }
            }
        }.execute();
    }

    private ProductInput productInput(String title, Product existing) {
        JTextField code = new JTextField(existing == null ? "" : existing.productCode());
        JTextField name = new JTextField(existing == null ? "" : existing.productName());
        JTextField category = new JTextField(existing == null ? "" : existing.category());
        JTextField price = new JTextField(existing == null ? "" : existing.price().toPlainString());
        JPanel panel = new JPanel(new GridLayout(0, 2, 5, 5));
        panel.add(new JLabel("Mã:")); panel.add(code); panel.add(new JLabel("Tên:")); panel.add(name);
        panel.add(new JLabel("Danh mục:")); panel.add(category); panel.add(new JLabel("Giá:")); panel.add(price);
        if (JOptionPane.showConfirmDialog(this, panel, title, JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return null;
        try {
            return new ProductInput(code.getText().trim(), name.getText().trim(), category.getText().trim(),
                    new java.math.BigDecimal(price.getText().trim()));
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Giá không hợp lệ."); return null;
        }
    }

    private Product selectedProduct() {
        int row = table.getSelectedRow();
        if (row < 0) { JOptionPane.showMessageDialog(this, "Hãy chọn sản phẩm."); return null; }
        return products.get(table.convertRowIndexToModel(row));
    }

    private void showError(Exception ex) {
        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
        JOptionPane.showMessageDialog(this, cause.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
    }
}
