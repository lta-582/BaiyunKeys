package cn.baiyunkeys.app.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;

/**
 * GATT 回调（Java 实现）。
 *
 * 之所以用 Java 而非 Kotlin：Kotlin K2 编译器在同一个类中同时覆盖
 * 废弃的 3 参 onCharacteristicRead 与新的 3 参 onCharacteristicChanged 时，
 * 会误报 "overrides nothing"（K2 override 解析缺陷）。Java 编译器无此问题。
 *
 * 所有回调转发到 BleManager 的对应方法，由其在内部切主线程处理。
 */
@SuppressLint("MissingPermission")
public class GattCallback extends BluetoothGattCallback {

    private final BleManager manager;

    public GattCallback(BleManager manager) {
        this.manager = manager;
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        manager.onConnectionStateChange(gatt, status, newState);
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        manager.onServicesDiscovered(gatt, status);
    }

    /** Android 13+ 走这里 */
    @Override
    public void onCharacteristicRead(BluetoothGatt gatt,
                                     BluetoothGattCharacteristic characteristic,
                                     byte[] value,
                                     int status) {
        manager.onCharacteristicRead(value, status);
    }

    /** Android 12 及以下走这里 */
    @Deprecated
    @Override
    public void onCharacteristicRead(BluetoothGatt gatt,
                                     BluetoothGattCharacteristic characteristic,
                                     int status) {
        manager.onCharacteristicRead(
                characteristic != null ? characteristic.getValue() : null, status);
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt,
                                      BluetoothGattCharacteristic characteristic,
                                      int status) {
        manager.onCharacteristicWrite(status);
    }

    /** Android 13+ 走这里 */
    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt,
                                        BluetoothGattCharacteristic characteristic,
                                        byte[] value) {
        manager.onCharacteristicChanged(value);
    }

    /** Android 12 及以下走这里 */
    @Deprecated
    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt,
                                        BluetoothGattCharacteristic characteristic) {
        if (characteristic != null) {
            manager.onCharacteristicChanged(characteristic.getValue());
        }
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt,
                                  BluetoothGattDescriptor descriptor,
                                  int status) {
        manager.onDescriptorWrite(status);
    }
}
