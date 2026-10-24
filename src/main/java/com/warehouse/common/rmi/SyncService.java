package com.warehouse.common.rmi;

import com.warehouse.common.dto.SyncEvent;
import com.warehouse.common.model.InventoryTransaction;
import com.warehouse.common.model.Product;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.UUID;

/**
 * Interface đồng bộ trực tiếp qua Java RMI giữa 2 Server (Server A và Server B).
 * Sử dụng DTO Serializable (Product, InventoryTransaction) thay cho Jackson JSON và Outbox table.
 */
public interface SyncService extends Remote {

    /**
     * Kiểm tra trạng thái hoạt động của Server đối diện.
     */
    boolean ping() throws RemoteException;

    /**
     * Đồng bộ thêm mới sản phẩm sang Server đối diện.
     */
    boolean syncCreateProduct(Product product) throws RemoteException;

    /**
     * Đồng bộ cập nhật thông tin sản phẩm sang Server đối diện.
     */
    boolean syncUpdateProduct(Product product) throws RemoteException;

    /**
     * Đồng bộ xóa mềm sản phẩm sang Server đối diện.
     */
    boolean syncDeleteProduct(UUID productId, long fencingToken) throws RemoteException;

    /**
     * Đồng bộ cập nhật tồn kho và lưu lịch sử giao dịch sang Server đối diện.
     */
    boolean syncInventory(UUID productId, int newQuantity, long fencingToken,
                          InventoryTransaction transaction) throws RemoteException;

    /**
     * Phương thức tương thích ngược (deprecated).
     */
    @Deprecated
    default boolean applyEvent(SyncEvent event) throws RemoteException {
        return false;
    }
}
