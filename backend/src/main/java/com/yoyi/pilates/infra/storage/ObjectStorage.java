package com.yoyi.pilates.infra.storage;
import java.io.IOException;
public interface ObjectStorage {
 void put(String key,byte[] data,String type)throws IOException;
 byte[] read(String key)throws IOException;
}
