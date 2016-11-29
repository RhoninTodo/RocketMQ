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
package com.alibaba.rocketmq.client.consumer.rebalance;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

import com.alibaba.rocketmq.client.consumer.AllocateMessageQueueStrategy;
import com.alibaba.rocketmq.client.log.ClientLogger;
import com.alibaba.rocketmq.common.message.MessageQueue;


/**
 * Average Hashing queue algorithm
 *
 * @author fuchong<yubao.fyb@alibaba-inc.com>
 * @author manhong.yqd<manhong.yqd@taobao.com>
 * @since 2013-7-24
 */
public class AllocateMessageQueueAveragely implements AllocateMessageQueueStrategy {
    private final Logger log = ClientLogger.getLog();


    @Override
    public String getName() {
        return "AVG";
    }


    @Override
    public List<MessageQueue> allocate(String consumerGroup, String currentCID, List<MessageQueue> mqAll,
                                       List<String> cidAll) {
        if (currentCID == null || currentCID.length() < 1) {
            throw new IllegalArgumentException("currentCID is empty");
        }
        if (mqAll == null || mqAll.isEmpty()) {
            throw new IllegalArgumentException("mqAll is null or mqAll empty");
        }
        if (cidAll == null || cidAll.isEmpty()) {
            throw new IllegalArgumentException("cidAll is null or cidAll empty");
        }

        List<MessageQueue> result = new ArrayList<MessageQueue>();
        if (!cidAll.contains(currentCID)) {
            log.info("[BUG] ConsumerGroup: {} The consumerId: {} not in cidAll: {}", //
                    consumerGroup, //
                    currentCID,//
                    cidAll);
            return result;
        }

        //以下类似于分页算法，但有一些不同。根据该consumer在consumer队列的位置，计算你应该消费queues中的哪些queue。
        int index = cidAll.indexOf(currentCID);
        int mod = mqAll.size() % cidAll.size();
        int averageSize = //如果队列数 < 消费者数量，每个消费者消费1个队列；
                mqAll.size() <= cidAll.size() ? 1 : (mod > 0 && index < mod ? mqAll.size() / cidAll.size()
                        + 1 : mqAll.size() / cidAll.size());//队列数 > 消费者，不能整除，前mod个消费者会多消费一个队列；整除就都一样了
        int startIndex = (mod > 0 && index < mod) ? index * averageSize : index * averageSize + mod;//相当于页起始
        int range = Math.min(averageSize, mqAll.size() - startIndex);//相当于页大小
        for (int i = 0; i < range; i++) {
            result.add(mqAll.get((startIndex + i) % mqAll.size()));
        }
        return result;//返回页数据，也就是本consumer应该消费的那些queue
    }
}
