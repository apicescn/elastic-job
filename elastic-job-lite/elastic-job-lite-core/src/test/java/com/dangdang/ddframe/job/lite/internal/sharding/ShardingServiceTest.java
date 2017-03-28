/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
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
 * </p>
 */

package com.dangdang.ddframe.job.lite.internal.sharding;

import com.dangdang.ddframe.job.config.JobCoreConfiguration;
import com.dangdang.ddframe.job.config.simple.SimpleJobConfiguration;
import com.dangdang.ddframe.job.lite.api.strategy.JobShardingResult;
import com.dangdang.ddframe.job.lite.api.strategy.JobShardingUnit;
import com.dangdang.ddframe.job.lite.api.strategy.impl.AverageAllocationJobShardingStrategy;
import com.dangdang.ddframe.job.lite.config.LiteJobConfiguration;
import com.dangdang.ddframe.job.lite.fixture.TestSimpleJob;
import com.dangdang.ddframe.job.lite.internal.config.ConfigurationService;
import com.dangdang.ddframe.job.lite.internal.election.LeaderElectionService;
import com.dangdang.ddframe.job.lite.internal.execution.ExecutionNode;
import com.dangdang.ddframe.job.lite.internal.execution.ExecutionService;
import com.dangdang.ddframe.job.lite.internal.schedule.JobRegistry;
import com.dangdang.ddframe.job.lite.internal.server.ServerService;
import com.dangdang.ddframe.job.lite.internal.storage.JobNodeStorage;
import com.dangdang.ddframe.job.lite.internal.storage.TransactionExecutionCallback;
import com.dangdang.ddframe.job.util.env.LocalHostService;
import org.apache.curator.framework.api.transaction.CuratorTransactionBridge;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;
import org.apache.curator.framework.api.transaction.TransactionCreateBuilder;
import org.apache.curator.framework.api.transaction.TransactionDeleteBuilder;
import org.apache.zookeeper.CreateMode;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.unitils.util.ReflectionUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class ShardingServiceTest {
    
    @Mock
    private JobNodeStorage jobNodeStorage;
    
    @Mock
    private LocalHostService localHostService;
    
    @Mock
    private LeaderElectionService leaderElectionService;
    
    @Mock
    private ConfigurationService configService;
    
    @Mock
    private ExecutionService executionService;
    
    @Mock
    private ServerService serverService;
    
    private final ShardingService shardingService = new ShardingService(null, "test_job");
    
    @Before
    public void setUp() throws NoSuchFieldException {
        MockitoAnnotations.initMocks(this);
        ReflectionUtils.setFieldValue(shardingService, "jobNodeStorage", jobNodeStorage);
        ReflectionUtils.setFieldValue(shardingService, "localHostService", localHostService);
        ReflectionUtils.setFieldValue(shardingService, "leaderElectionService", leaderElectionService);
        ReflectionUtils.setFieldValue(shardingService, "configService", configService);
        ReflectionUtils.setFieldValue(shardingService, "executionService", executionService);
        ReflectionUtils.setFieldValue(shardingService, "serverService", serverService);
        when(localHostService.getIp()).thenReturn("mockedIP");
        when(localHostService.getHostName()).thenReturn("mockedHostName");
        JobRegistry.getInstance().addJobInstanceId("test_job", "test_job_instance_id");
    }
    
    @Test
    public void assertSetReshardingFlag() {
        shardingService.setReshardingFlag();
        verify(jobNodeStorage).createJobNodeIfNeeded("leader/sharding/necessary");
    }
    
    @Test
    public void assertIsNeedSharding() {
        when(jobNodeStorage.isJobNodeExisted("leader/sharding/necessary")).thenReturn(true);
        assertTrue(shardingService.isNeedSharding());
        verify(jobNodeStorage).isJobNodeExisted("leader/sharding/necessary");
    }
    
    @Test
    public void assertShardingWhenUnnecessary() {
        when(configService.load(false)).thenReturn(LiteJobConfiguration.newBuilder(new SimpleJobConfiguration(JobCoreConfiguration.newBuilder("test_job", "0/1 * * * * ?", 3).build(),
                TestSimpleJob.class.getCanonicalName())).monitorExecution(true).jobShardingStrategyClass(AverageAllocationJobShardingStrategy.class.getCanonicalName()).build());
        when(serverService.getAvailableShardingUnits()).thenReturn(Collections.singletonList(new JobShardingUnit("mockedIP", "test_job_instance_id")));
        when(jobNodeStorage.isJobNodeExisted("leader/sharding/necessary")).thenReturn(false);
        shardingService.shardingIfNecessary();
        verify(serverService).getAvailableShardingUnits();
        verify(jobNodeStorage).isJobNodeExisted("leader/sharding/necessary");
    }
    
    @Test
    public void assertShardingWithoutAvailableServers() {
        when(configService.load(false)).thenReturn(LiteJobConfiguration.newBuilder(new SimpleJobConfiguration(JobCoreConfiguration.newBuilder("test_job", "0/1 * * * * ?", 3).build(),
                TestSimpleJob.class.getCanonicalName())).monitorExecution(true).jobShardingStrategyClass(AverageAllocationJobShardingStrategy.class.getCanonicalName()).build());
        when(serverService.getAllShardingUnits()).thenReturn(Arrays.asList(new JobShardingUnit("ip1", "test_job_instance_id"), new JobShardingUnit("ip2", "test_job_instance_id")));
        when(serverService.getAvailableShardingUnits()).thenReturn(Collections.<JobShardingUnit>emptyList());
        shardingService.shardingIfNecessary();
        verify(serverService).getAvailableShardingUnits();
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/0/ip");
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/0/instance");
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/1/ip");
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/1/instance");
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/2/ip");
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/2/instance");
        verify(jobNodeStorage, times(0)).isJobNodeExisted("leader/sharding/necessary");
    }
    
    @Test
    public void assertShardingWhenIsNotLeaderAndIsShardingProcessing() {
        when(configService.load(false)).thenReturn(LiteJobConfiguration.newBuilder(new SimpleJobConfiguration(JobCoreConfiguration.newBuilder("test_job", "0/1 * * * * ?", 3).build(),
                TestSimpleJob.class.getCanonicalName())).monitorExecution(true).jobShardingStrategyClass(AverageAllocationJobShardingStrategy.class.getCanonicalName()).build());
        when(serverService.getAvailableShardingUnits()).thenReturn(Collections.singletonList(new JobShardingUnit("mockedIP", "test_job_instance_id")));
        when(jobNodeStorage.isJobNodeExisted("leader/sharding/necessary")).thenReturn(true, true, false, false);
        when(leaderElectionService.isLeader()).thenReturn(false);
        when(jobNodeStorage.isJobNodeExisted("leader/sharding/processing")).thenReturn(true, false);
        shardingService.shardingIfNecessary();
        verify(serverService).getAvailableShardingUnits();
        verify(jobNodeStorage, times(4)).isJobNodeExisted("leader/sharding/necessary");
        verify(jobNodeStorage, times(2)).isJobNodeExisted("leader/sharding/processing");
    }
    
    @Test
    public void assertShardingNecessaryWhenMonitorExecutionEnabled() {
        when(serverService.getAvailableShardingUnits()).thenReturn(Collections.singletonList(new JobShardingUnit("ip1", "test_job_instance_id")));
        when(serverService.getAllShardingUnits()).thenReturn(Arrays.asList(new JobShardingUnit("ip1", "test_job_instance_id"), new JobShardingUnit("ip2", "test_job_instance_id")));
        when(jobNodeStorage.isJobNodeExisted("leader/sharding/necessary")).thenReturn(true);
        when(leaderElectionService.isLeader()).thenReturn(true);
        when(configService.load(false)).thenReturn(LiteJobConfiguration.newBuilder(new SimpleJobConfiguration(JobCoreConfiguration.newBuilder("test_job", "0/1 * * * * ?", 3).build(),
                TestSimpleJob.class.getCanonicalName())).monitorExecution(true).jobShardingStrategyClass(AverageAllocationJobShardingStrategy.class.getCanonicalName()).build());
        when(executionService.hasRunningItems()).thenReturn(true, false);
        shardingService.shardingIfNecessary();
        verify(serverService).getAvailableShardingUnits();
        verify(jobNodeStorage).isJobNodeExisted("leader/sharding/necessary");
        verify(leaderElectionService).isLeader();
        verify(configService).load(false);
        verify(executionService, times(2)).hasRunningItems();
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/0/ip");
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/0/instance");
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/1/ip");
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/1/instance");
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/2/ip");
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/2/instance");
        verify(jobNodeStorage).fillEphemeralJobNode("leader/sharding/processing", "");
        verify(jobNodeStorage).executeInTransaction(any(TransactionExecutionCallback.class));
    }
    
    @Test
    public void assertShardingNecessaryWhenMonitorExecutionDisabled() throws Exception {
        when(serverService.getAvailableShardingUnits()).thenReturn(Collections.singletonList(new JobShardingUnit("ip1", "test_job_instance_id")));
        when(serverService.getAllShardingUnits()).thenReturn(Arrays.asList(new JobShardingUnit("ip1", "test_job_instance_id"), new JobShardingUnit("ip2", "test_job_instance_id")));
        when(jobNodeStorage.isJobNodeExisted("leader/sharding/necessary")).thenReturn(true);
        when(leaderElectionService.isLeader()).thenReturn(true);
        when(configService.load(false)).thenReturn(LiteJobConfiguration.newBuilder(new SimpleJobConfiguration(JobCoreConfiguration.newBuilder("test_job", "0/1 * * * * ?", 3).build(),
                TestSimpleJob.class.getCanonicalName())).monitorExecution(false).jobShardingStrategyClass(AverageAllocationJobShardingStrategy.class.getCanonicalName()).build());
        shardingService.shardingIfNecessary();
        verify(serverService).getAvailableShardingUnits();
        verify(jobNodeStorage).isJobNodeExisted("leader/sharding/necessary");
        verify(leaderElectionService).isLeader();
        verify(configService).load(false);
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/0/ip");
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/0/instance");
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/1/ip");
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/1/instance");
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/2/ip");
        verify(jobNodeStorage).removeJobNodeIfExisted("execution/2/instance");
        verify(jobNodeStorage).fillEphemeralJobNode("leader/sharding/processing", "");
        verify(jobNodeStorage).executeInTransaction(any(TransactionExecutionCallback.class));
    }
    
    @Test
    public void assertGetLocalHostShardingItemsWhenNodeExisted() {
        when(configService.load(true)).thenReturn(LiteJobConfiguration.newBuilder(new SimpleJobConfiguration(JobCoreConfiguration.newBuilder("test_job", "0/1 * * * * ?", 3).build(),
                TestSimpleJob.class.getCanonicalName())).monitorExecution(true).jobShardingStrategyClass(AverageAllocationJobShardingStrategy.class.getCanonicalName()).build());
        when(jobNodeStorage.isJobNodeExisted("servers/mockedIP/instances/test_job_instance_id/sharding")).thenReturn(true);
        when(jobNodeStorage.getJobNodeDataDirectly("execution/0/ip")).thenReturn("mockedIP");
        when(jobNodeStorage.getJobNodeDataDirectly("execution/0/instance")).thenReturn("test_job_instance_id");
        when(jobNodeStorage.getJobNodeDataDirectly("execution/1/ip")).thenReturn("mockedIP");
        when(jobNodeStorage.getJobNodeDataDirectly("execution/1/instance")).thenReturn("test_job_instance_id");
        when(jobNodeStorage.getJobNodeDataDirectly("execution/2/ip")).thenReturn("mockedIP");
        when(jobNodeStorage.getJobNodeDataDirectly("execution/2/instance")).thenReturn("test_job_instance_id");
        assertThat(shardingService.getLocalHostShardingItems(), is(Arrays.asList(0, 1, 2)));
    }
    
    @Test
    public void assertGetLocalHostShardingWhenNodeNotExisted() {
        when(configService.load(true)).thenReturn(LiteJobConfiguration.newBuilder(new SimpleJobConfiguration(JobCoreConfiguration.newBuilder("test_job", "0/1 * * * * ?", 3).build(),
                TestSimpleJob.class.getCanonicalName())).monitorExecution(true).jobShardingStrategyClass(AverageAllocationJobShardingStrategy.class.getCanonicalName()).build());
        assertThat(shardingService.getLocalHostShardingItems(), is(Collections.EMPTY_LIST));
        verify(jobNodeStorage).getJobNodeDataDirectly("execution/0/ip");
        verify(jobNodeStorage, times(0)).getJobNodeDataDirectly("execution/0/instance");
        verify(jobNodeStorage).getJobNodeDataDirectly("execution/1/ip");
        verify(jobNodeStorage, times(0)).getJobNodeDataDirectly("execution/1/instance");
        verify(jobNodeStorage).getJobNodeDataDirectly("execution/2/ip");
        verify(jobNodeStorage, times(0)).getJobNodeDataDirectly("execution/2/instance");
    }
    
    @Test
    public void assertPersistShardingInfoTransactionExecutionCallback() throws Exception {
        CuratorTransactionFinal curatorTransactionFinal = mock(CuratorTransactionFinal.class);
        TransactionCreateBuilder transactionCreateBuilder = mock(TransactionCreateBuilder.class);
        TransactionDeleteBuilder transactionDeleteBuilder = mock(TransactionDeleteBuilder.class);
        CuratorTransactionBridge curatorTransactionBridge = mock(CuratorTransactionBridge.class);
        when(curatorTransactionFinal.create()).thenReturn(transactionCreateBuilder);
        when(transactionCreateBuilder.withMode(CreateMode.EPHEMERAL)).thenReturn(transactionCreateBuilder);
        when(configService.load(true)).thenReturn(LiteJobConfiguration.newBuilder(new SimpleJobConfiguration(JobCoreConfiguration.newBuilder("test_job", "0/1 * * * * ?", 3).build(),
                TestSimpleJob.class.getCanonicalName())).monitorExecution(true).jobShardingStrategyClass(AverageAllocationJobShardingStrategy.class.getCanonicalName()).build());
        when(transactionCreateBuilder.forPath("/test_job/execution/0/ip", "host0".getBytes())).thenReturn(curatorTransactionBridge);
        when(transactionCreateBuilder.forPath("/test_job/execution/0/instance", "test_job_instance_id".getBytes())).thenReturn(curatorTransactionBridge);
        when(transactionCreateBuilder.forPath("/test_job/execution/1/ip", "host0".getBytes())).thenReturn(curatorTransactionBridge);
        when(transactionCreateBuilder.forPath("/test_job/execution/1/instance", "test_job_instance_id".getBytes())).thenReturn(curatorTransactionBridge);
        when(transactionCreateBuilder.forPath("/test_job/execution/2/ip", "host0".getBytes())).thenReturn(curatorTransactionBridge);
        when(transactionCreateBuilder.forPath("/test_job/execution/2/instance", "test_job_instance_id".getBytes())).thenReturn(curatorTransactionBridge);
        when(curatorTransactionBridge.and()).thenReturn(curatorTransactionFinal);
        when(curatorTransactionFinal.delete()).thenReturn(transactionDeleteBuilder);
        when(transactionDeleteBuilder.forPath("/test_job/leader/sharding/necessary")).thenReturn(curatorTransactionBridge);
        when(curatorTransactionBridge.and()).thenReturn(curatorTransactionFinal);
        when(curatorTransactionFinal.delete()).thenReturn(transactionDeleteBuilder);
        when(transactionDeleteBuilder.forPath("/test_job/leader/sharding/processing")).thenReturn(curatorTransactionBridge);
        when(curatorTransactionBridge.and()).thenReturn(curatorTransactionFinal);
        Collection<JobShardingResult> shardingItems = new LinkedList<>();
        shardingItems.add(new JobShardingResult(new JobShardingUnit("host0", "test_job_instance_id"), Arrays.asList(0, 1, 2)));
        ShardingService.PersistShardingInfoTransactionExecutionCallback actual = shardingService.new PersistShardingInfoTransactionExecutionCallback(shardingItems);
        actual.execute(curatorTransactionFinal);
        verify(curatorTransactionFinal, times(6)).create();
        verify(curatorTransactionFinal, times(2)).delete();
        verify(transactionDeleteBuilder).forPath("/test_job/leader/sharding/necessary");
        verify(transactionDeleteBuilder).forPath("/test_job/leader/sharding/processing");
        verify(curatorTransactionBridge, times(8)).and();
    }
    
    @Test
    public void assertHasShardingInfoInOfflineServers() throws NoSuchFieldException {
        when(configService.load(true)).thenReturn(LiteJobConfiguration.newBuilder(new SimpleJobConfiguration(JobCoreConfiguration.newBuilder("test_job", "0/1 * * * * ?", 3).build(),
                TestSimpleJob.class.getCanonicalName())).monitorExecution(true).jobShardingStrategyClass(AverageAllocationJobShardingStrategy.class.getCanonicalName()).build());
        when(serverService.getAllShardingUnits()).thenReturn(Arrays.asList(new JobShardingUnit("host0", "test_job_instance_id"), new JobShardingUnit("host1", "test_job_instance_id")));
        when(jobNodeStorage.getJobNodeDataDirectly(ExecutionNode.getIpNode(0))).thenReturn("host0");
        when(jobNodeStorage.getJobNodeDataDirectly(ExecutionNode.getInstanceNode(0))).thenReturn("test_job_instance_id");
        when(jobNodeStorage.getJobNodeDataDirectly(ExecutionNode.getIpNode(1))).thenReturn("host1");
        when(jobNodeStorage.getJobNodeDataDirectly(ExecutionNode.getInstanceNode(1))).thenReturn("test_job_instance_id");
        when(serverService.isOffline("host0", "test_job_instance_id")).thenReturn(false);
        when(serverService.isOffline("host1", "test_job_instance_id")).thenReturn(true);
        assertTrue(shardingService.hasShardingInfoInOfflineServers());
    }
    
    @Test
    public void assertHasNotShardingInfoInOfflineServers() throws NoSuchFieldException {
        when(configService.load(true)).thenReturn(LiteJobConfiguration.newBuilder(new SimpleJobConfiguration(JobCoreConfiguration.newBuilder("test_job", "0/1 * * * * ?", 3).build(),
                TestSimpleJob.class.getCanonicalName())).monitorExecution(true).jobShardingStrategyClass(AverageAllocationJobShardingStrategy.class.getCanonicalName()).build());
        when(serverService.getAllShardingUnits()).thenReturn(Arrays.asList(new JobShardingUnit("host0", "test_job_instance_id"), new JobShardingUnit("host1", "test_job_instance_id")));
        when(serverService.isOffline("host0", "test_job_instance_id")).thenReturn(false);
        assertFalse(shardingService.hasShardingInfoInOfflineServers());
    }
}
