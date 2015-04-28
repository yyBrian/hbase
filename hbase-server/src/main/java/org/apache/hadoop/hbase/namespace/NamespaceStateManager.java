/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable
 * law or agreed to in writing, software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 * for the specific language governing permissions and limitations under the License.
 */
package org.apache.hadoop.hbase.namespace;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.NamespaceDescriptor;
import org.apache.hadoop.hbase.RegionTransition;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.client.MetaScanner;
import org.apache.hadoop.hbase.exceptions.DeserializationException;
import org.apache.hadoop.hbase.executor.EventType;
import org.apache.hadoop.hbase.master.MasterServices;
import org.apache.hadoop.hbase.master.TableNamespaceManager;
import org.apache.hadoop.hbase.quotas.QuotaExceededException;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.zookeeper.ZKAssign;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperListener;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

/**
 * NamespaceStateManager manages state (in terms of quota) of all the namespaces. It contains a
 * cache which is updated based on the hooks in the NamespaceAuditor class.
 */
@InterfaceAudience.Private
class NamespaceStateManager extends ZooKeeperListener {

  private static final Log LOG = LogFactory.getLog(NamespaceStateManager.class);
  private ConcurrentMap<String, NamespaceTableAndRegionInfo> nsStateCache;
  private MasterServices master;
  private volatile boolean initialized = false;

  public NamespaceStateManager(MasterServices masterServices, ZooKeeperWatcher zkw) {
    super(zkw);
    nsStateCache = new ConcurrentHashMap<String, NamespaceTableAndRegionInfo>();
    master = masterServices;
  }

  /**
   * Starts the NamespaceStateManager. The boot strap of cache is done in the post master start hook
   * of the NamespaceAuditor class.
   * @throws IOException Signals that an I/O exception has occurred.
   */
  public void start() throws IOException {
    LOG.info("Namespace State Manager started.");
    initialize();
    watcher.registerListenerFirst(this);
  }

  /**
   * Gets an instance of NamespaceTableAndRegionInfo associated with namespace.
   * @param The name of the namespace
   * @return An instance of NamespaceTableAndRegionInfo.
   */
  public NamespaceTableAndRegionInfo getState(String name) {
    return nsStateCache.get(name);
  }

  /**
   * Check if adding a region violates namespace quota, if not update namespace cache.
   * @param TableName
   * @param regionName
   * @param incr
   * @return true, if region can be added to table.
   * @throws IOException Signals that an I/O exception has occurred.
   */
  synchronized boolean checkAndUpdateNamespaceRegionCount(TableName name, byte[] regionName,
      int incr) throws IOException {
    String namespace = name.getNamespaceAsString();
    NamespaceDescriptor nspdesc = getNamespaceDescriptor(namespace);
    if (nspdesc != null) {
      NamespaceTableAndRegionInfo currentStatus;
      currentStatus = getState(namespace);
      if (incr > 0
          && currentStatus.getRegionCount() >= TableNamespaceManager.getMaxRegions(nspdesc)) {
        LOG.warn("The region " + Bytes.toStringBinary(regionName)
            + " cannot be created. The region count  will exceed quota on the namespace. "
            + "This may be transient, please retry later if there are any ongoing split"
            + " operations in the namespace.");
        return false;
      }
      NamespaceTableAndRegionInfo nsInfo = nsStateCache.get(namespace);
      if (nsInfo != null) {
        nsInfo.incRegionCountForTable(name, incr);
      } else {
        LOG.warn("Namespace state found null for namespace : " + namespace);
      }
    }
    return true;
  }

  private NamespaceDescriptor getNamespaceDescriptor(String namespaceAsString) {
    try {
      return this.master.getNamespaceDescriptor(namespaceAsString);
    } catch (IOException e) {
      LOG.error("Error while fetching namespace descriptor for namespace : " + namespaceAsString);
      return null;
    }
  }

  synchronized void checkAndUpdateNamespaceTableCount(TableName table, int numRegions)
      throws IOException {
    String namespace = table.getNamespaceAsString();
    NamespaceDescriptor nspdesc = getNamespaceDescriptor(namespace);
    if (nspdesc != null) {
      NamespaceTableAndRegionInfo currentStatus;
      currentStatus = getState(nspdesc.getName());
      if ((currentStatus.getTables().size()) >= TableNamespaceManager.getMaxTables(nspdesc)) {
        throw new QuotaExceededException("The table " + table.getNameAsString()
            + "cannot be created as it would exceed maximum number of tables allowed "
            + " in the namespace.  The total number of tables permitted is "
            + TableNamespaceManager.getMaxTables(nspdesc));
      }
      if ((currentStatus.getRegionCount() + numRegions) > TableNamespaceManager
          .getMaxRegions(nspdesc)) {
        throw new QuotaExceededException("The table " + table.getNameAsString()
            + " is not allowed to have " + numRegions
            + " regions. The total number of regions permitted is only "
            + TableNamespaceManager.getMaxRegions(nspdesc) + ", while current region count is "
            + currentStatus.getRegionCount()
            + ". This may be transient, please retry later if there are any"
            + " ongoing split operations in the namespace.");
      }
    } else {
      throw new IOException("Namespace Descriptor found null for " + namespace
          + " This is unexpected.");
    }
    addTable(table, numRegions);
  }

  NamespaceTableAndRegionInfo addNamespace(String namespace) {
    if (!nsStateCache.containsKey(namespace)) {
      NamespaceTableAndRegionInfo a1 = new NamespaceTableAndRegionInfo(namespace);
      nsStateCache.put(namespace, a1);
    }
    return nsStateCache.get(namespace);
  }

  /**
   * Delete the namespace state.
   * @param An instance of NamespaceTableAndRegionInfo
   */
  void deleteNamespace(String namespace) {
    this.nsStateCache.remove(namespace);
  }

  private void addTable(TableName tableName, int regionCount) throws IOException {
    NamespaceTableAndRegionInfo info = nsStateCache.get(tableName.getNamespaceAsString());
    if (info != null) {
      info.addTable(tableName, regionCount);
    } else {
      throw new IOException("Bad state : Namespace quota information not found for namespace : "
          + tableName.getNamespaceAsString());
    }
  }

  synchronized void removeTable(TableName tableName) {
    NamespaceTableAndRegionInfo info = nsStateCache.get(tableName.getNamespaceAsString());
    if (info != null) {
      info.removeTable(tableName);
    }
  }

  /**
   * Initialize namespace state cache by scanning meta table.
   */
  private void initialize() throws IOException {
    List<NamespaceDescriptor> namespaces = this.master.listNamespaceDescriptors();
    for (NamespaceDescriptor namespace : namespaces) {
      addNamespace(namespace.getName());
      List<TableName> tables = this.master.listTableNamesByNamespace(namespace.getName());
      for (TableName table : tables) {
        if (table.isSystemTable()) {
          continue;
        }
        int regionCount = 0;
        Map<HRegionInfo, ServerName> regions =
            MetaScanner.allTableRegions(this.master.getConnection(), table);
        for (HRegionInfo info : regions.keySet()) {
          if (!info.isSplit()) {
            regionCount++;
          }
        }
        addTable(table, regionCount);
      }
    }
    LOG.info("Finished updating state of " + nsStateCache.size() + " namespaces. ");
    initialized = true;
  }

  boolean isInitialized() {
    return initialized;
  }

  public synchronized void removeRegionFromTable(HRegionInfo hri) throws IOException {
    String namespace = hri.getTable().getNamespaceAsString();
    NamespaceTableAndRegionInfo nsInfo = nsStateCache.get(namespace);
    if (nsInfo != null) {
      nsInfo.decrementRegionCountForTable(hri.getTable(), 1);
    } else {
      throw new IOException("Namespace state found null for namespace : " + namespace);
    }
  }

  @Override
  public void nodeCreated(String path) {
    checkSplittingOrMergingNode(path);
  }

  @Override
  public void nodeChildrenChanged(String path) {
    checkSplittingOrMergingNode(path);
  }

  private void checkSplittingOrMergingNode(String path) {
    String msg = "Error reading data from zookeeper, ";
    try {
      if (path.startsWith(watcher.assignmentZNode)) {
        List<String> children =
            ZKUtil.listChildrenAndWatchForNewChildren(watcher, watcher.assignmentZNode);
        if (children != null) {
          for (String child : children) {
            Stat stat = new Stat();
            byte[] data =
                ZKAssign.getDataAndWatch(watcher, ZKUtil.joinZNode(watcher.assignmentZNode, child),
                  stat);
            if (data != null) {
              RegionTransition rt = RegionTransition.parseFrom(data);
              if (rt.getEventType().equals(EventType.RS_ZK_REQUEST_REGION_SPLIT)) {
                TableName table = HRegionInfo.getTable(rt.getRegionName());
                if (!checkAndUpdateNamespaceRegionCount(table, rt.getRegionName(), 1)) {
                  ZKUtil.deleteNode(watcher, ZKUtil.joinZNode(watcher.assignmentZNode, child));
                }
              } else if (rt.getEventType().equals(EventType.RS_ZK_REQUEST_REGION_MERGE)) {
                TableName table = HRegionInfo.getTable(rt.getRegionName());
                checkAndUpdateNamespaceRegionCount(table, rt.getRegionName(), -1);
              }
            }
          }
        }
      }
    } catch (KeeperException ke) {
      LOG.error(msg, ke);
      watcher.abort(msg, ke);
    } catch (DeserializationException e) {
      LOG.error(msg, e);
      watcher.abort(msg, e);
    } catch (IOException e) {
      LOG.error(msg, e);
      watcher.abort(msg, e);
    }
  }
}
