package com.warehouse.client.ui;

import com.warehouse.client.rmi.WarehouseClient;
import com.warehouse.common.dto.LockLease;
import com.warehouse.common.dto.ProductInput;
import com.warehouse.common.model.InventoryTransaction;
import com.warehouse.common.model.Product;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class DashboardFrame extends JFrame {
    private final WarehouseClient client;
    private final String clientId;
    private final String username;
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Mã SP", "Tên sản phẩm", "Danh mục", "Giá", "Tồn kho"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JTextField search = new JTextField(20);
    private final JLabel status = new JLabel("Sẵn sàng");
    private final JLabel peerStatus = new JLabel("Server còn lại: đang kiểm tra...");
    private final JLabel productTitle = new JLabel("Chưa chọn sản phẩm");
    private final JLabel productDetails = new JLabel("Chọn một dòng trong bảng.");
    private final JLabel lockStatus = new JLabel("Khóa của bạn: chưa có");
    private final JButton lockButton = new JButton("Khóa sản phẩm");
    private final JButton releaseButton = new JButton("Giải phóng khóa");
    private final JButton editButton = new JButton("Sửa sản phẩm");
    private final JButton importButton = new JButton("Nhập kho");
    private final JButton exportButton = new JButton("Xuất kho");
    private final JButton deleteButton = new JButton("Xóa sản phẩm");
    private final JButton historyButton = new JButton("Lịch sử nhập / xuất");
    private final JButton addButton = new JButton("+ Thêm sản phẩm");
    private final Timer heartbeatTimer;
    private List<Product> products = List.of();
    private LockLease currentLease;
    private boolean busy;
    private boolean heartbeatRunning;

    public DashboardFrame(WarehouseClient client, String clientId, String username) {
        super("Quản lý kho - " + username);
        this.client = client;
        this.clientId = clientId;
        this.username = username;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                handleWindowClosing();
            }
        });
        setMinimumSize(new Dimension(980, 620));
        setSize(1180, 700);
        setLocationRelativeTo(null);
        buildUi();
        heartbeatTimer = new Timer(10_000, e -> renewLease());
        heartbeatTimer.start();
        loadProducts();
        refreshPeer();
    }

    private void handleWindowClosing() {
        heartbeatTimer.stop();
        LockLease lease = currentLease;
        currentLease = null;
        setVisible(false);
        if (lease != null) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    client.locks().release(lease.lockToken(), clientId);
                } catch (Exception ignored) {
                    // Lease will automatically expire on server via TTL if network is lost
                }
            });
            try {
                future.get(1500, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // Timeout or interruption: proceed with closing safely without hanging Swing EDT
            }
        }
        dispose();
        System.exit(0);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        JLabel title = new JLabel("Quản lý kho hàng");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 23f));
        JPanel heading = new JPanel(new BorderLayout(8, 5));
        heading.add(title, BorderLayout.NORTH);
        heading.add(new JLabel("Nhân viên: " + username + "    |    Client: " + clientId), BorderLayout.SOUTH);
        root.add(heading, BorderLayout.NORTH);

        table.setRowHeight(32);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateSelection();
        });
        JPanel searchBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton find = new JButton("Tìm");
        JButton refresh = new JButton("Làm mới");
        searchBar.add(new JLabel("Mã hoặc tên:"));
        searchBar.add(search);
        searchBar.add(find);
        searchBar.add(refresh);
        searchBar.add(addButton);
        search.addActionListener(e -> loadProducts());
        find.addActionListener(e -> loadProducts());
        refresh.addActionListener(e -> { search.setText(""); loadProducts(); refreshPeer(); });
        addButton.addActionListener(e -> createProduct());
        JPanel list = new JPanel(new BorderLayout(0, 10));
        list.add(searchBar, BorderLayout.NORTH);
        list.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
        actions.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Sản phẩm đã chọn"),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        productTitle.setFont(productTitle.getFont().deriveFont(Font.BOLD, 17f));
        for (JComponent component : new JComponent[]{productTitle, productDetails, lockStatus}) {
            component.setAlignmentX(Component.LEFT_ALIGNMENT);
            actions.add(component);
            actions.add(Box.createVerticalStrut(9));
        }
        actions.add(new JSeparator());
        actions.add(Box.createVerticalStrut(11));
        for (JButton button : new JButton[]{lockButton, releaseButton, editButton, importButton,
                exportButton, historyButton, deleteButton}) {
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            actions.add(button);
            actions.add(Box.createVerticalStrut(7));
        }
        JLabel hint = new JLabel("<html>Để sửa, nhập, xuất hoặc xóa:<br>chọn sản phẩm → khóa → thao tác.</html>");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.add(Box.createVerticalStrut(10));
        actions.add(hint);
        lockButton.addActionListener(e -> acquireLock());
        releaseButton.addActionListener(e -> releaseLock());
        editButton.addActionListener(e -> editProduct());
        importButton.addActionListener(e -> changeInventory(true));
        exportButton.addActionListener(e -> changeInventory(false));
        historyButton.addActionListener(e -> showHistory());
        deleteButton.addActionListener(e -> deleteProduct());
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, list, actions);
        split.setResizeWeight(1.0);
        split.setDividerLocation(800);
        split.setBorder(null);
        root.add(split, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        footer.add(status, BorderLayout.WEST);
        footer.add(peerStatus, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
        updateSelection();
    }

    private void loadProducts() {
        String term = search.getText().trim();
        status.setText("Đang tải danh sách sản phẩm...");
        new SwingWorker<List<Product>, Void>() {
            @Override protected List<Product> doInBackground() throws Exception {
                return client.warehouse().listProducts(term);
            }
            @Override protected void done() {
                try {
                    UUID selected = selectedProductId();
                    products = get();
                    model.setRowCount(0);
                    for (Product product : products) {
                        model.addRow(new Object[]{product.productCode(), product.productName(),
                                product.category(), product.price(), product.quantity()});
                    }
                    if (selected != null) {
                        for (int i = 0; i < products.size(); i++) {
                            if (products.get(i).id().equals(selected)) {
                                int view = table.convertRowIndexToView(i);
                                if (view >= 0) table.setRowSelectionInterval(view, view);
                                break;
                            }
                        }
                    }
                    status.setText(products.isEmpty() ?
                            (term.isEmpty() ? "Chưa có sản phẩm trong kho." : "Không có sản phẩm phù hợp.") :
                            "Đã tải " + products.size() + " sản phẩm.");
                    updateSelection();
                } catch (Exception ex) { showError(ex); }
            }
        }.execute();
    }

    private void refreshPeer() {
        peerStatus.setText("Server còn lại: đang kiểm tra...");
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() throws Exception {
                return client.warehouse().isPeerAvailable();
            }
            @Override protected void done() {
                try {
                    boolean available = get();
                    peerStatus.setText(available ? "Server còn lại: đã kết nối" : "Server còn lại: chưa kết nối");
                    peerStatus.setForeground(available ? new Color(33, 116, 79) : new Color(166, 95, 27));
                } catch (Exception ex) { peerStatus.setText("Server còn lại: không kiểm tra được"); }
            }
        }.execute();
    }

    private void acquireLock() {
        Product product = selectedProduct();
        if (product == null) return;
        runRemote("Đang xin khóa " + product.productCode() + "...",
                () -> client.locks().acquire(product.id(), clientId), lease -> {
                    currentLease = lease;
                    status.setText("Đã khóa " + product.productCode() + ". Có thể thao tác.");
                });
    }

    private void releaseLock() {
        LockLease lease = currentLease;
        if (lease == null) return;
        runRemote("Đang giải phóng khóa...", () -> {
            client.locks().release(lease.lockToken(), clientId);
            return null;
        }, ignored -> {
            if (currentLease == lease) currentLease = null;
            status.setText("Đã giải phóng khóa.");
        });
    }

    private void renewLease() {
        LockLease lease = currentLease;
        if (lease == null || heartbeatRunning) return;
        heartbeatRunning = true;
        new SwingWorker<LockLease, Void>() {
            @Override protected LockLease doInBackground() throws Exception {
                return client.locks().heartbeat(lease.lockToken(), clientId);
            }
            @Override protected void done() {
                heartbeatRunning = false;
                if (currentLease != lease) return;
                try { currentLease = get(); updateSelection(); }
                catch (Exception ex) {
                    currentLease = null;
                    status.setText("Khóa đã hết hạn hoặc mất kết nối. Hãy khóa lại.");
                    updateSelection();
                }
            }
        }.execute();
    }

    private void createProduct() {
        ProductInput input = productInput("Thêm sản phẩm", null);
        if (input == null) return;
        runRemote("Đang thêm sản phẩm...", () -> client.warehouse().createProduct(input, clientId, username),
                product -> { status.setText("Đã thêm " + product.productCode() + "."); loadProducts(); });
    }

    private void editProduct() {
        Product product = selectedProduct();
        LockLease lease = leaseFor(product);
        if (lease == null) return;
        ProductInput input = productInput("Sửa " + product.productCode(), product);
        if (input == null) return;
        runRemote("Đang lưu sản phẩm...", () -> client.warehouse().updateProduct(product.id(), input,
                lease.lockToken(), lease.fencingToken(), clientId, username),
                updated -> { status.setText("Đã lưu " + updated.productCode() + "."); loadProducts(); });
    }

    private void changeInventory(boolean importing) {
        Product product = selectedProduct();
        LockLease lease = leaseFor(product);
        if (lease == null) return;
        String value = JOptionPane.showInputDialog(this,
                (importing ? "Nhập kho" : "Xuất kho") + " - " + product.productCode()
                        + "\nTồn kho hiện tại: " + product.quantity() + "\nSố lượng:");
        if (value == null) return;
        int quantity;
        try {
            quantity = Integer.parseInt(value.trim());
            if (quantity <= 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Nhập số nguyên lớn hơn 0.", "Số lượng không hợp lệ",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!importing && quantity > product.quantity()) {
            JOptionPane.showMessageDialog(this, "Số lượng xuất vượt tồn kho hiện tại.",
                    "Không thể xuất kho", JOptionPane.WARNING_MESSAGE);
            return;
        }
        runRemote(importing ? "Đang nhập kho..." : "Đang xuất kho...",
                () -> client.warehouse().changeInventory(product.id(), quantity, importing,
                        lease.lockToken(), lease.fencingToken(), clientId, username),
                transaction -> { status.setText("Tồn kho mới: " + transaction.quantityAfter() + "."); loadProducts(); });
    }

    private void deleteProduct() {
        Product product = selectedProduct();
        LockLease lease = leaseFor(product);
        if (lease == null) return;
        if (JOptionPane.showConfirmDialog(this, "Xóa sản phẩm " + product.productCode() + "?",
                "Xác nhận xóa mềm", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.YES_OPTION) return;
        runRemote("Đang xóa sản phẩm...", () -> {
            client.warehouse().deleteProduct(product.id(), lease.lockToken(), lease.fencingToken(), clientId, username);
            boolean released = false;
            try {
                client.locks().release(lease.lockToken(), clientId);
                released = true;
            } catch (Exception ignored) {
                // Thao tác xóa đã hoàn tất trên server, nếu release lỗi thì lease sẽ tự hủy qua TTL
            }
            return released;
        }, released -> {
            if (currentLease == lease) {
                currentLease = null;
            }
            if (Boolean.TRUE.equals(released)) {
                status.setText("Đã xóa " + product.productCode() + " và giải phóng khóa.");
            } else {
                status.setText("Đã xóa " + product.productCode() + " (khóa sẽ tự hết hạn theo TTL).");
            }
            loadProducts();
        });
    }

    private void showHistory() {
        Product product = selectedProduct();
        if (product == null) return;
        runRemote("Đang tải lịch sử...", () -> client.warehouse().transactionHistory(product.id()),
                rows -> showHistoryDialog(product, rows));
    }

    private void showHistoryDialog(Product product, List<InventoryTransaction> rows) {
        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Sản phẩm này chưa có giao dịch nhập/xuất.",
                    "Lịch sử " + product.productCode(), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        DefaultTableModel history = new DefaultTableModel(
                new Object[]{"Mã giao dịch", "Loại", "Số lượng", "Trước", "Sau", "Người làm", "Thời gian"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
                .withZone(ZoneId.systemDefault());
        for (InventoryTransaction tx : rows) {
            history.addRow(new Object[]{tx.transactionCode(), tx.transactionType(), tx.quantity(),
                    tx.quantityBefore(), tx.quantityAfter(), tx.createdBy(), formatter.format(tx.createdAt())});
        }
        JTable historyTable = new JTable(history);
        historyTable.setRowHeight(28);
        JScrollPane pane = new JScrollPane(historyTable);
        pane.setPreferredSize(new Dimension(850, 350));
        JOptionPane.showMessageDialog(this, pane, "Lịch sử " + product.productCode(),
                JOptionPane.PLAIN_MESSAGE);
    }

    private ProductInput productInput(String title, Product existing) {
        JTextField code = new JTextField(existing == null ? "" : existing.productCode(), 20);
        code.setEditable(existing == null);
        JTextField name = new JTextField(existing == null ? "" : existing.productName(), 20);
        JTextField category = new JTextField(existing == null ? "" : existing.category(), 20);
        JTextField price = new JTextField(existing == null ? "" : existing.price().toPlainString(), 20);
        JPanel fields = new JPanel(new GridLayout(0, 2, 10, 10));
        fields.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        fields.add(new JLabel("Mã sản phẩm:")); fields.add(code);
        fields.add(new JLabel("Tên sản phẩm:")); fields.add(name);
        fields.add(new JLabel("Danh mục:")); fields.add(category);
        fields.add(new JLabel("Giá:")); fields.add(price);
        while (JOptionPane.showConfirmDialog(this, fields, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                String productCode = code.getText().trim();
                String productName = name.getText().trim();
                String productCategory = category.getText().trim();
                BigDecimal productPrice = new BigDecimal(price.getText().trim());
                if (productCode.isEmpty() || productName.isEmpty() || productCategory.isEmpty()
                        || productPrice.signum() < 0) throw new IllegalArgumentException();
                return new ProductInput(productCode, productName, productCategory, productPrice);
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(this, "Điền đủ mã, tên, danh mục và giá không âm.",
                        "Dữ liệu không hợp lệ", JOptionPane.WARNING_MESSAGE);
            }
        }
        return null;
    }

    private Product selectedProduct() {
        int viewRow = table.getSelectedRow();
        return viewRow < 0 ? null : products.get(table.convertRowIndexToModel(viewRow));
    }

    private UUID selectedProductId() {
        Product product = selectedProduct();
        return product == null ? null : product.id();
    }

    private LockLease leaseFor(Product product) {
        if (product != null && currentLease != null && currentLease.resourceId().equals(product.id())) {
            return currentLease;
        }
        JOptionPane.showMessageDialog(this, "Hãy khóa sản phẩm này trước khi thao tác.",
                "Cần khóa", JOptionPane.INFORMATION_MESSAGE);
        return null;
    }

    private void updateSelection() {
        Product product = selectedProduct();
        productTitle.setText(product == null ? "Chưa chọn sản phẩm" :
                product.productCode() + " - " + product.productName());
        productDetails.setText(product == null ? "Chọn một dòng trong bảng." :
                "Tồn kho: " + product.quantity() + "    |    Giá: " + product.price());
        lockStatus.setText(currentLease == null ? "Khóa của bạn: chưa có" :
                "Đang khóa: " + lockedProductCode() + " (đến " +
                        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
                                .format(currentLease.expiresAt()) + ")");
        boolean selected = product != null;
        boolean ownsSelected = selected && currentLease != null
                && currentLease.resourceId().equals(product.id());
        lockButton.setEnabled(!busy && selected && currentLease == null);
        addButton.setEnabled(!busy);
        releaseButton.setEnabled(!busy && currentLease != null);
        editButton.setEnabled(!busy && ownsSelected);
        importButton.setEnabled(!busy && ownsSelected);
        exportButton.setEnabled(!busy && ownsSelected);
        deleteButton.setEnabled(!busy && ownsSelected);
        historyButton.setEnabled(!busy && selected);
    }

    private String lockedProductCode() {
        for (Product product : products) {
            if (product.id().equals(currentLease.resourceId())) return product.productCode();
        }
        return "sản phẩm khác";
    }

    private <T> void runRemote(String message, RemoteCall<T> call, java.util.function.Consumer<T> onSuccess) {
        if (busy) return;
        busy = true;
        status.setText(message);
        updateSelection();
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return call.execute(); }
            @Override protected void done() {
                busy = false;
                try { onSuccess.accept(get()); }
                catch (Exception ex) { showError(ex); }
                updateSelection();
            }
        }.execute();
    }

    private void showError(Exception exception) {
        Throwable cause = exception;
        while (cause instanceof ExecutionException && cause.getCause() != null) cause = cause.getCause();
        status.setText("Thao tác thất bại: " + cause.getMessage());
        JOptionPane.showMessageDialog(this, cause.getMessage(), "Không thể thực hiện", JOptionPane.ERROR_MESSAGE);
    }

    public LockLease currentLease() {
        return currentLease;
    }

    public void setCurrentLeaseForTest(LockLease lease) {
        this.currentLease = lease;
        updateSelection();
    }

    public Timer heartbeatTimer() {
        return heartbeatTimer;
    }

    @FunctionalInterface private interface RemoteCall<T> { T execute() throws Exception; }
}
