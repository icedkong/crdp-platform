package com.crdp.framework.crypto;

import java.util.List;

/** 业务模块向重加密作业登记本模块加密列的扩展点（E03-T05）。患者等业务表随 E13 落地时各自提供 Spring Bean； 作业按登记清单逐列巡检，未登记的表不会被巡检。 */
public interface EncryptedColumnProvider {

    List<EncryptedColumn> encryptedColumns();
}
