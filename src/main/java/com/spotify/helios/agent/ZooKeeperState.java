/**
 * Copyright (C) 2013 Spotify AB
 */

package com.spotify.helios.agent;

import com.google.common.base.Throwables;

import com.netflix.curator.framework.CuratorFramework;
import com.netflix.curator.framework.recipes.cache.PathChildrenCache;
import com.netflix.curator.framework.recipes.cache.PathChildrenCacheEvent;
import com.netflix.curator.framework.recipes.cache.PathChildrenCacheListener;
import com.spotify.helios.common.coordination.CuratorInterface;
import com.spotify.helios.common.coordination.Paths;
import com.spotify.helios.common.descriptors.AgentJobDescriptor;
import com.spotify.helios.common.descriptors.JobStatus;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.spotify.helios.common.descriptors.Descriptor.parse;
import static org.apache.zookeeper.KeeperException.NoNodeException;

public class ZooKeeperState extends AbstractState {

  private static final Logger log = LoggerFactory.getLogger(ZooKeeperState.class);

  private final PathChildrenCache containers;
  private final CuratorInterface client;
  private final String agent;

  public ZooKeeperState(final CuratorInterface client, final String agent) {
    this.client = checkNotNull(client);
    this.agent = checkNotNull(agent);
    this.containers = client.pathChildrenCache(Paths.configAgentJobs(agent), true);
    containers.getListenable().addListener(new ContainersListener());
  }

  private String jobId(final String path) {
    final String prefix = Paths.configAgentJobs(agent) + "/";
    return path.replaceFirst(prefix, "");
  }

  @Override
  public void setJobStatus(final String name, final JobStatus state) {
    log.debug("setting job status: {}", state);

    final String path = Paths.statusAgentJob(agent, name);

    try {
      // Check if the node already exists.
      final Stat stat = client.stat(path);

      if (stat != null) {
        // The node already exists, overwrite it.
        client.setData(path, state.toJsonBytes());
      } else {
        client.createAndSetData(path, state.toJsonBytes());
      }
    } catch (KeeperException e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public JobStatus getJobStatus(final String name) {
    final String path = Paths.statusAgentJob(agent, name);
    try {
      final byte[] data = client.getData(path);
      if (data == null) {
        // No data, treat that as no state
        return null;
      }
      return parse(data, JobStatus.class);
    } catch (NoNodeException e) {
      // No node -> no state
      return null;
    } catch (IOException e) {
      // State couldn't be parsed, treat that as no state
      return null;
    } catch (KeeperException e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public void removeJobStatus(final String name) {
    log.debug("removing job status: name={}", name);
    try {
      client.delete(Paths.statusAgentJob(agent, name));
    } catch (NoNodeException e) {
      log.debug("application node did not exist");
    } catch (KeeperException e) {
      throw Throwables.propagate(e);
    }
  }

  public void start() {
    log.debug("starting");
    try {
      containers.start();
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  private class ContainersListener implements PathChildrenCacheListener {

    @Override
    public void childEvent(final CuratorFramework client, final PathChildrenCacheEvent event)
        throws Exception {
      log.debug("agent jobs event: event={}", event);

      switch (event.getType()) {
        case CHILD_ADDED: {
          final byte[] data = event.getData().getData();
          final String name = jobId(event.getData().getPath());
          final AgentJobDescriptor descriptor = parse(data, AgentJobDescriptor.class);
          doAddJob(name, descriptor);
          break;
        }
        case CHILD_UPDATED: {
          final byte[] data = event.getData().getData();
          final String name = jobId(event.getData().getPath());
          final AgentJobDescriptor descriptor = parse(data, AgentJobDescriptor.class);
          doUpdateJob(name, descriptor);
          break;
        }
        case CHILD_REMOVED: {
          final String name = jobId(event.getData().getPath());
          doRemoveJob(name);
          break;
        }
      }
    }
  }
}