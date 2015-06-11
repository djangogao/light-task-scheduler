package com.lts.job.core.failstore.rocksdb;

import com.lts.job.core.cluster.Config;
import com.lts.job.core.domain.KVPair;
import com.lts.job.core.failstore.FailStore;
import com.lts.job.core.failstore.FailStoreException;
import com.lts.job.core.commons.file.FileLock;
import com.lts.job.core.commons.file.FileUtils;
import com.lts.job.core.commons.utils.CollectionUtils;
import com.lts.job.core.commons.utils.JSONUtils;
import org.rocksdb.*;
import org.rocksdb.util.SizeUnit;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Robert HG (254963746@qq.com) on 5/27/15.
 */
public class RocksdbFailStore implements FailStore {

    private RocksDB db = null;
    private Options options;
    private String failStorePath;
    private FileLock lock;

    public RocksdbFailStore(Config config) {
        failStorePath = config.getFailStorePath() + "rocksdb/";
        FileUtils.createDirIfNotExist(failStorePath);
        options = new Options();
        options.setCreateIfMissing(true)
                .setWriteBufferSize(8 * SizeUnit.KB)
                .setMaxWriteBufferNumber(3)
                .setMaxBackgroundCompactions(10)
                .setCompressionType(CompressionType.SNAPPY_COMPRESSION)
                .setCompactionStyle(CompactionStyle.UNIVERSAL);

        Filter bloomFilter = new BloomFilter(10);
        BlockBasedTableConfig tableConfig = new BlockBasedTableConfig();
        tableConfig.setBlockCacheSize(64 * SizeUnit.KB)
                .setFilter(bloomFilter)
                .setCacheNumShardBits(6)
                .setBlockSizeDeviation(5)
                .setBlockRestartInterval(10)
                .setCacheIndexAndFilterBlocks(true)
                .setHashIndexAllowCollision(false)
                .setBlockCacheCompressedSize(64 * SizeUnit.KB)
                .setBlockCacheCompressedNumShardBits(10);

        options.setTableFormatConfig(tableConfig);
        lock = new FileLock(failStorePath + "___db.lock");
        // TODO other settings
    }

    @Override
    public void open() throws FailStoreException {
        try {
            lock.tryLock();
            db = RocksDB.open(options, failStorePath);
        } catch (Exception e) {
            throw new FailStoreException(e);
        }
    }

    @Override
    public void put(String key, Object value) throws FailStoreException {
        String valueString = JSONUtils.toJSONString(value);
        WriteOptions writeOpts = new WriteOptions();
        try {
            writeOpts.setSync(true);
            writeOpts.setDisableWAL(true);
            db.put(writeOpts, key.getBytes("UTF-8"), valueString.getBytes("UTF-8"));
        } catch (Exception e) {
            throw new FailStoreException(e);
        } finally {
            writeOpts.dispose();
        }
    }

    @Override
    public void delete(String key) throws FailStoreException {
        try {
            db.remove(key.getBytes("UTF-8"));
        } catch (Exception e) {
            throw new FailStoreException(e);
        }
    }

    @Override
    public void delete(List<String> keys) throws FailStoreException {
        if (CollectionUtils.isEmpty(keys)) {
            return;
        }
        for (String key : keys) {
            delete(key);
        }
    }

    @Override
    public <T> List<KVPair<String, T>> fetchTop(int size, Type type) throws FailStoreException {
        RocksIterator iterator = null;
        try {
            List<KVPair<String, T>> list = new ArrayList<KVPair<String, T>>(size);
            iterator = db.newIterator();
            for (iterator.seekToLast(); iterator.isValid(); iterator.prev()) {
                iterator.status();
                String key = new String(iterator.key(), "UTF-8");
                T value = JSONUtils.parse(new String(iterator.value(), "UTF-8"), type);
                KVPair<String, T> pair = new KVPair<String, T>(key, value);
                list.add(pair);
                if (list.size() >= size) {
                    break;
                }
            }
            return list;
        } catch (Exception e) {
            throw new FailStoreException(e);
        } finally {
            if (iterator != null) {
                iterator.dispose();
            }
        }
    }

    @Override
    public void close() throws FailStoreException {
        try {
            if (db != null) {
                db.close();
            }
        } catch (Exception e) {
            throw new FailStoreException(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public void destroy() throws FailStoreException {
        try {
            db.close();
            options.dispose();
        } catch (Exception e) {
            throw new FailStoreException(e);
        } finally {
            lock.delete();
        }
    }
}
