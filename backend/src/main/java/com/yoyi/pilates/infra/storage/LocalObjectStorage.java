package com.yoyi.pilates.infra.storage;
import java.io.IOException;import java.nio.file.*;
import org.springframework.stereotype.Component;import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Component @ConditionalOnProperty(name="yoyi.storage.type",havingValue="local",matchIfMissing=true)
public class LocalObjectStorage implements ObjectStorage {
 private final Path root;
 public LocalObjectStorage(@Value("${yoyi.upload.dir:./uploads}")String directory){root=Path.of(directory).toAbsolutePath().normalize();}
 private Path resolve(String key){Path path=root.resolve(key).normalize();if(!path.startsWith(root))throw new IllegalArgumentException("invalid key");return path;}
 public void put(String key,byte[] bytes,String type)throws IOException {Path path=resolve(key);Files.createDirectories(path.getParent());Files.write(path,bytes,StandardOpenOption.CREATE_NEW);}
 public byte[] read(String key)throws IOException{return Files.readAllBytes(resolve(key));}
}
