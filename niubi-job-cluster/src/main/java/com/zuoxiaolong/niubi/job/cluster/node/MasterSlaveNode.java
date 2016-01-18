/*
 * Copyright 2002-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zuoxiaolong.niubi.job.cluster.node;

import com.zuoxiaolong.niubi.job.api.ApiFactory;
import com.zuoxiaolong.niubi.job.api.curator.ApiFactoryImpl;
import com.zuoxiaolong.niubi.job.api.data.JobData;
import com.zuoxiaolong.niubi.job.api.data.NodeData;
import com.zuoxiaolong.niubi.job.api.helper.EventHelper;
import com.zuoxiaolong.niubi.job.core.exception.NiubiException;
import com.zuoxiaolong.niubi.job.core.helper.LoggerHelper;
import com.zuoxiaolong.niubi.job.core.helper.StringHelper;
import com.zuoxiaolong.niubi.job.scheduler.container.Container;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.ExponentialBackoffRetry;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 主从模式实现
 *
 * @author Xiaolong Zuo
 * @since 16/1/9 14:43
 */
public class MasterSlaveNode extends AbstractRemoteJobNode {

    private CuratorFramework client;

    private final LeaderSelector leaderSelector;

    private RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, Integer.MAX_VALUE);

    private ApiFactory apiFactory;

    private String nodePath;

    private PathChildrenCache jobCache;

    private PathChildrenCache nodeCache;

    public MasterSlaveNode(String zookeeperAddresses, String jarRepertoryUrl, String[] propertiesFileNames) {
        super(jarRepertoryUrl, propertiesFileNames);
        this.client = CuratorFrameworkFactory.newClient(zookeeperAddresses, retryPolicy);
        this.client.start();

        this.apiFactory = new ApiFactoryImpl(client);

        this.nodePath = this.apiFactory.nodeApi().createStandbyNode(new NodeData.Data(getIp()));
        this.nodeCache = new PathChildrenCache(client, apiFactory.pathApi().getStandbyNodePath(), true);
        this.nodeCache.getListenable().addListener(createNodeCacheListener());
        try {
            this.nodeCache.start();
        } catch (Exception e) {
            LoggerHelper.error("path children path start failed.", e);
            throw new NiubiException(e);
        }

        this.jobCache = new PathChildrenCache(client, apiFactory.pathApi().getStandbyJobPath(), true);
        this.jobCache.getListenable().addListener(createJobCacheListener());
        try {
            this.jobCache.start();
        } catch (Exception e) {
            LoggerHelper.error("path children path start failed.", e);
            throw new NiubiException(e);
        }

        this.leaderSelector = new LeaderSelector(client, apiFactory.pathApi().getStandbyMasterPath(), createLeaderSelectorListener());
        leaderSelector.autoRequeue();
    }

    private PathChildrenCacheListener createNodeCacheListener() {
        return new PathChildrenCacheListener() {
            @Override
            public synchronized void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {
                if (!leaderSelector.hasLeadership()) {
                    return;
                }
                if (EventHelper.isChildRemoveEvent(event)) {
                    releaseJobs(event.getData().getPath());
                }
            }
        };
    }

    private void releaseJobs(String nodePath) {
        NodeData nodeData = apiFactory.nodeApi().selectStandbyNode(nodePath);
        for (String path : nodeData.getData().getJobPaths()) {
            JobData.Data data = apiFactory.jobApi().selectStandbyJob(path).getData();
            data.setNodePath(null);
            apiFactory.jobApi().updateStandbyJob(data.getGroupName(), data.getJobName(), data);
        }
    }

    private LeaderSelectorListener createLeaderSelectorListener() {
        return new LeaderSelectorListener() {

            private final AtomicInteger leaderCount = new AtomicInteger();

            private Object mutex = new Object();

            public void takeLeadership(CuratorFramework curatorFramework) throws Exception {
                LoggerHelper.info(getIp() + " is now the leader ,and has been leader " + this.leaderCount.getAndIncrement() + " time(s) before.");
                try {
                    synchronized (mutex) {
                        NodeData.Data nodeData = new NodeData.Data(getIp());
                        nodeData.setState("Master");
                        apiFactory.nodeApi().updateStandbyNode(nodePath, nodeData);
                        LoggerHelper.info(getIp() + " has been updated. [" + nodeData + "]");
                        mutex.wait();
                    }
                } catch (Exception e) {
                    LoggerHelper.info(getIp() + " startup failed,relinquish leadership.");
                } finally {
                    LoggerHelper.info(getIp() + " relinquishing leadership.");
                }
            }

            public void stateChanged(CuratorFramework client, ConnectionState newState) {
                LoggerHelper.info(getIp() + " state change [" + newState + "]");
                if (!newState.isConnected()) {
                    synchronized (mutex) {
                        releaseJobs(nodePath);
                        mutex.notify();
                    }
                }
            }

        };
    }

    private PathChildrenCacheListener createJobCacheListener() {
        return new PathChildrenCacheListener() {
            @Override
            public synchronized void childEvent(CuratorFramework clientInner, PathChildrenCacheEvent event) throws Exception {
                if (EventHelper.isChildModifyEvent(event)) {
                    return;
                }
                JobData jobData = new JobData(event.getData());
                if (StringHelper.isEmpty(jobData.getData().getOperation())) {
                    return;
                }
                JobData.Data data = jobData.getData();
                if (data.isUnknownOperation()) {
                    return;
                }
                NodeData.Data nodeData = apiFactory.nodeApi().selectStandbyNode(nodePath).getData();
                boolean hasLeadership = leaderSelector != null && leaderSelector.hasLeadership();
                if (hasLeadership && StringHelper.isEmpty(data.getNodePath())) {
                    List<NodeData> nodeDataList = apiFactory.nodeApi().selectAllStandbyNodes();
                    Collections.sort(nodeDataList);
                    data.setNodePath(nodeDataList.get(0).getPath());
                    apiFactory.jobApi().updateStandbyJob(data.getGroupName(), data.getJobName(), data);
                }
                if ((EventHelper.isChildUpdateEvent(event) || EventHelper.isChildAddEvent(event))
                        && nodePath.equals(data.getNodePath())) {
                    executeOperation(nodeData, data);
                }
            }
        };
    }

    private void executeOperation(NodeData.Data nodeData, JobData.Data data) {
        try {
            if (data.isStart() || data.isRestart()) {
                if (data.isRestart()) {
                    Container container = getContainer(data.getOriginalJarFileName(), data.getPackagesToScan(), data.isSpring());
                    container.scheduleManager().shutdown(data.getGroupName(), data.getJobName());
                    nodeData.setRunningJobCount(nodeData.getRunningJobCount() - 1);
                }
                Container container = getContainer(data.getJarFileName(), data.getPackagesToScan(), data.isSpring());
                container.scheduleManager().startupManual(data.getGroupName(), data.getJobName(), data.getCron(), data.getMisfirePolicy());
                nodeData.setRunningJobCount(nodeData.getRunningJobCount() + 1);
                data.setState("Startup");
            } else {
                Container container = getContainer(data.getOriginalJarFileName(), data.getPackagesToScan(), data.isSpring());
                container.scheduleManager().shutdown(data.getGroupName(), data.getJobName());
                nodeData.setRunningJobCount(nodeData.getRunningJobCount() - 1);
                data.setState("Pause");
            }
            data.operateSuccess();
            apiFactory.jobApi().updateStandbyJob(data.getGroupName(), data.getJobName(), data);
            apiFactory.nodeApi().updateStandbyNode(nodePath, nodeData);
        } catch (Throwable e) {
            LoggerHelper.error("handle operation failed. " + data, e);
            data.operateFailed(e.getClass().getName() + ":" + e.getMessage());
            apiFactory.jobApi().updateStandbyJob(data.getGroupName(), data.getJobName(), data);
        }
    }

    @Override
    public void join() {
        leaderSelector.start();
    }

    @Override
    public void exit() {
        leaderSelector.close();
        client.close();
    }

}
