package com.openlocal.office.core;

import java.io.*;
import java.util.*;
import java.util.zip.*;

final class ZipPackage {
  private static final long DEFAULT_MAX_UNCOMPRESSED_BYTES = 96L * 1024L * 1024L;
  private static final int MAX_ENTRIES = 10000;

  final LinkedHashMap<String, byte[]> entries;
  private ZipPackage(LinkedHashMap<String, byte[]> entries) { this.entries = entries; }

  static ZipPackage read(byte[] bytes) throws IOException {
    return read(bytes, DEFAULT_MAX_UNCOMPRESSED_BYTES);
  }

  static ZipPackage read(byte[] bytes, long maxUncompressedBytes) throws IOException {
    if (maxUncompressedBytes < 1) throw new IllegalArgumentException("maxUncompressedBytes must be positive");
    LinkedHashMap<String, byte[]> map = new LinkedHashMap<>();
    long total = 0;
    int entryCount = 0;
    try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (ZipEntry e; (e = zin.getNextEntry()) != null;) {
        if (e.isDirectory()) continue;
        if (++entryCount > MAX_ENTRIES) throw new IOException("Office package contains too many entries");
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        for (int n; (n = zin.read(buf)) != -1;) {
          total += n;
          if (total > maxUncompressedBytes) throw new IOException("Office package decompressed content exceeds safe memory limit");
          entry.write(buf, 0, n);
        }
        map.put(e.getName(), entry.toByteArray());
      }
    }
    if (map.isEmpty()) throw new IOException("Not a ZIP/Office package");
    return new ZipPackage(map);
  }

  byte[] get(String name) throws IOException {
    byte[] b = entries.get(name); if (b == null) throw new IOException("Missing package entry: " + name); return b;
  }

  byte[] write() throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ZipOutputStream zout = new ZipOutputStream(bos)) {
      for (Map.Entry<String, byte[]> e : entries.entrySet()) {
        ZipEntry ze = new ZipEntry(e.getKey());
        zout.putNextEntry(ze); zout.write(e.getValue()); zout.closeEntry();
      }
    }
    return bos.toByteArray();
  }
}
