/**
 * Copyright (C) 2010-2013 Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.rocketmq.store.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.rocketmq.common.constant.LoggerName;
import com.alibaba.rocketmq.store.MapedFile;


/**
 * 存储具体消息索引信息的文件
 * 
 * @author shijia.wxr<vintage.wang@gmail.com>
 * @since 2013-7-21
 */
public class IndexFile {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.StoreLoggerName);
    private static int HASH_SLOT_SIZE = 4;
    private static int INDEX_SIZE = 20;
    private static int INVALID_INDEX = 0;
    private final int hashSlotNum; // hash槽数，相当于hash桶大小，默认500w
    private final int indexNum; // 能存储索引的数量
    private final MapedFile mapedFile;
    private final FileChannel fileChannel;
    private final MappedByteBuffer mappedByteBuffer;
    private final IndexHeader indexHeader;

    //IndexFile分成了三个区：Header(40) + slots(500w*4) + indexs(num*20)
    public IndexFile(final String fileName, final int hashSlotNum, final int indexNum,
            final long endPhyOffset, final long endTimestamp) throws IOException {
        int fileTotalSize =
                IndexHeader.INDEX_HEADER_SIZE + (hashSlotNum * HASH_SLOT_SIZE) + (indexNum * INDEX_SIZE);
        this.mapedFile = new MapedFile(fileName, fileTotalSize);
        this.fileChannel = this.mapedFile.getFileChannel();
        this.mappedByteBuffer = this.mapedFile.getMappedByteBuffer();
        this.hashSlotNum = hashSlotNum;
        this.indexNum = indexNum;

        ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
        this.indexHeader = new IndexHeader(byteBuffer);

        if (endPhyOffset > 0) {
            this.indexHeader.setBeginPhyOffset(endPhyOffset);
            this.indexHeader.setEndPhyOffset(endPhyOffset);
        }

        if (endTimestamp > 0) {
            this.indexHeader.setBeginTimestamp(endTimestamp);
            this.indexHeader.setEndTimestamp(endTimestamp);
        }
    }


    public String getFileName() {
        return this.mapedFile.getFileName();
    }


    public void load() {
        this.indexHeader.load();
    }


    public void flush() {
        long beginTime = System.currentTimeMillis();
        if (this.mapedFile.hold()) {
            this.indexHeader.updateByteBuffer();
            this.mappedByteBuffer.force();
            this.mapedFile.release();
            log.info("flush index file eclipse time(ms) " + (System.currentTimeMillis() - beginTime));
        }
    }


    /**
     * 当前索引文件是否写满
     */
    public boolean isWriteFull() {
        return this.indexHeader.getIndexCount() >= this.indexNum;
    }


    public boolean destroy(final long intervalForcibly) {
        return this.mapedFile.destroy(intervalForcibly);
    }


    /**
     * 如果返回false，表示需要创建新的索引文件
     */
    public boolean putKey(final String key, final long phyOffset, final long storeTimestamp) {
        if (this.indexHeader.getIndexCount() < this.indexNum) {
            int keyHash = indexKeyHashMethod(key);//冲突的索引会被链接到Next_Index_Offset(4bytes)
            int slotPos = keyHash % this.hashSlotNum;
            int absSlotPos = IndexHeader.INDEX_HEADER_SIZE + slotPos * HASH_SLOT_SIZE;

            FileLock fileLock = null;

            try {
                // TODO 是否是读写锁
                // fileLock = this.fileChannel.lock(absSlotPos, HASH_SLOT_SIZE,
                // false);
                int slotValue = this.mappedByteBuffer.getInt(absSlotPos);//put前slot上是否有值，有值=值，没值=0
                if (slotValue <= INVALID_INDEX || slotValue > this.indexHeader.getIndexCount()) {
                    slotValue = INVALID_INDEX;
                }

                long timeDiff = storeTimestamp - this.indexHeader.getBeginTimestamp();

                // 时间差存储单位由毫秒改为秒
                timeDiff = timeDiff / 1000;

                // 25000天后溢出
                if (this.indexHeader.getBeginTimestamp() <= 0) {
                    timeDiff = 0;
                }
                else if (timeDiff > Integer.MAX_VALUE) {
                    timeDiff = Integer.MAX_VALUE;
                }
                else if (timeDiff < 0) {
                    timeDiff = 0;
                }

                int absIndexPos =
                        IndexHeader.INDEX_HEADER_SIZE + this.hashSlotNum * HASH_SLOT_SIZE
                                + this.indexHeader.getIndexCount() * INDEX_SIZE;// IndexCount是自增的

                // 写入真正索引
                this.mappedByteBuffer.putInt(absIndexPos, keyHash);
                this.mappedByteBuffer.putLong(absIndexPos + 4, phyOffset);
                this.mappedByteBuffer.putInt(absIndexPos + 4 + 8, (int) timeDiff);
                this.mappedByteBuffer.putInt(absIndexPos + 4 + 8 + 4, slotValue);//解决hash冲突，前一个相同hash的索引被链接到4byte上

                // 更新哈希槽
                this.mappedByteBuffer.putInt(absSlotPos, this.indexHeader.getIndexCount());//将indexs区中的偏移放进hashCode(key)槽位置上

                // 第一次写入
                if (this.indexHeader.getIndexCount() <= 1) {
                    this.indexHeader.setBeginPhyOffset(phyOffset);
                    this.indexHeader.setBeginTimestamp(storeTimestamp);
                }

                this.indexHeader.incHashSlotCount();
                this.indexHeader.incIndexCount();
                this.indexHeader.setEndPhyOffset(phyOffset);
                this.indexHeader.setEndTimestamp(storeTimestamp);

                return true;
            }
            catch (Exception e) {
                log.error("putKey exception, Key: " + key + " KeyHashCode: " + key.hashCode(), e);
            }
            finally {
                if (fileLock != null) {
                    try {
                        fileLock.release();
                    }
                    catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        else {
            log.warn("putKey index count " + this.indexHeader.getIndexCount() + " index max num "
                    + this.indexNum);
        }

        return false;
    }


    public long getBeginTimestamp() {
        return this.indexHeader.getBeginTimestamp();
    }


    public long getEndTimestamp() {
        return this.indexHeader.getEndTimestamp();
    }


    public long getEndPhyOffset() {
        return this.indexHeader.getEndPhyOffset();
    }


    /**
     * 时间区间是否匹配
     * 其实就是三种情况的并集 <=> [begin, end] ∩ [indexHeader.begin, indexHeader.end] != ∅
     * suggest：end >= indexHead.begin && begin <= indexHeader.end <=> 也能保证必有交集
     */
    public boolean isTimeMatched(final long begin, final long end) {
        boolean result =
                begin < this.indexHeader.getBeginTimestamp() && end > this.indexHeader.getEndTimestamp();

        result =
                result
                        || (begin >= this.indexHeader.getBeginTimestamp() && begin <= this.indexHeader
                            .getEndTimestamp());

        result =
                result
                        || (end >= this.indexHeader.getBeginTimestamp() && end <= this.indexHeader
                            .getEndTimestamp());
        return result;
    }


    // 返回值是大于0
    public int indexKeyHashMethod(final String key) {
        int keyHash = key.hashCode();
        int keyHashPositive = Math.abs(keyHash);
        if (keyHashPositive < 0)
            keyHashPositive = 0;
        return keyHashPositive;
    }


    /**
     * 前提：入参时间区间在调用前已经匹配了当前索引文件的起始结束时间
     */
    //IndexFile分成了三个区：Header(40) + slots(500w*4) + indexs(num*20)
    //hashCode(key)找到对应的slot，用slot上的值去indexs区找索引数据。有点像innodb页结构，为什么要这样做？
    public void selectPhyOffset(final List<Long> phyOffsets, final String key, final int maxNum,
            final long begin, final long end, boolean lock) {
        if (this.mapedFile.hold()) {
            int keyHash = indexKeyHashMethod(key);//String.hashCode()
            int slotPos = keyHash % this.hashSlotNum;//该key在第几个slot上
            int absSlotPos = IndexHeader.INDEX_HEADER_SIZE + slotPos * HASH_SLOT_SIZE;//在文件中的绝对偏移

            FileLock fileLock = null;
            try {
                if (lock) {
                    // fileLock = this.fileChannel.lock(absSlotPos,
                    // HASH_SLOT_SIZE, true);
                }

                int slotValue = this.mappedByteBuffer.getInt(absSlotPos);//槽上的值
                // if (fileLock != null) {
                // fileLock.release();
                // fileLock = null;
                // }

                if (slotValue <= INVALID_INDEX || slotValue > this.indexHeader.getIndexCount()
                        || this.indexHeader.getIndexCount() <= 1) {
                    // TODO NOTFOUND
                }
                else {
                    for (int nextIndexToRead = slotValue;;) {
                        if (phyOffsets.size() >= maxNum) {
                            break;
                        }

                        int absIndexPos = //找到index区的位置
                                IndexHeader.INDEX_HEADER_SIZE + this.hashSlotNum * HASH_SLOT_SIZE
                                        + nextIndexToRead * INDEX_SIZE;

                        int keyHashRead = this.mappedByteBuffer.getInt(absIndexPos);
                        long phyOffsetRead = this.mappedByteBuffer.getLong(absIndexPos + 4);
                        // int转为long，避免下面计算时间差值时溢出
                        long timeDiff = (long) this.mappedByteBuffer.getInt(absIndexPos + 4 + 8);//只是强转long，操作还是getInt，position+=4
                        int prevIndexRead = this.mappedByteBuffer.getInt(absIndexPos + 4 + 8 + 4);//看build如何生成的

                        // 读到了未知数据
                        if (timeDiff < 0) {
                            break;
                        }

                        // 时间差存储的是秒，再还原为毫秒， long避免溢出
                        timeDiff *= 1000L;

                        long timeRead = this.indexHeader.getBeginTimestamp() + timeDiff;
                        boolean timeMatched = (timeRead >= begin) && (timeRead <= end);

                        if (keyHash == keyHashRead && timeMatched) {//校验hash key，校验时间戳
                            phyOffsets.add(phyOffsetRead);
                        }

                        if (prevIndexRead <= INVALID_INDEX
                                || prevIndexRead > this.indexHeader.getIndexCount()
                                || prevIndexRead == nextIndexToRead || timeRead < begin) {
                            break;
                        }

                        nextIndexToRead = prevIndexRead;//继续判断下一条索引
                    }
                }
            }
            catch (Exception e) {
                log.error("selectPhyOffset exception ", e);
            }
            finally {
                if (fileLock != null) {
                    try {
                        fileLock.release();
                    }
                    catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                this.mapedFile.release();
            }
        }
    }
}
