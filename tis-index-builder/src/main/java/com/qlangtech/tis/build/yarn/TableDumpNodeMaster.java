/**
 * Copyright (c) 2020 QingLang, Inc. <baisui@qlangtech.com>
 *
 * This program is free software: you can use, redistribute, and/or modify
 * it under the terms of the GNU Affero General Public License, version 3
 * or later ("AGPL"), as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.qlangtech.tis.build.yarn;

import com.qlangtech.tis.build.NodeMaster;
import com.qlangtech.tis.fullbuild.indexbuild.TaskContext;
import com.qlangtech.tis.offline.TableDumpFactory;
import com.qlangtech.tis.order.dump.task.SingleTableDumpTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Objects;

/**
 * 到数据库表Yarn服务端入口
 *
 * @author 百岁（baisui@qlangtech.com）
 * @create: 2020-04-15 15:25
 */
public class TableDumpNodeMaster extends NodeMaster {

    private static final Logger logger = LoggerFactory.getLogger(TableDumpNodeMaster.class);

    private final TableDumpFactory tableDumpFactory;

    public TableDumpNodeMaster(TableDumpFactory factory) {
        super(factory);
        this.tableDumpFactory = factory;
    }

    @Override
    protected void startExecute(TaskContext context) {
        Objects.requireNonNull(zkClient, "zkClient can not be null");
        Objects.requireNonNull(statusRpc, "statusRpc can not be null");
        SingleTableDumpTask tableDumpTask = new SingleTableDumpTask(tableDumpFactory, zkClient, statusRpc);
        tableDumpTask.map(context);
    }
}
