/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db.compaction.writers;

import java.util.Arrays;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.Directories;
import org.apache.cassandra.db.SerializationHeader;
import org.apache.cassandra.db.compaction.CompactionRealm;
import org.apache.cassandra.db.lifecycle.LifecycleTransaction;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.sstable.format.SSTableWriter;
import org.apache.cassandra.io.sstable.metadata.MetadataCollector;

/**
 * CompactionAwareWriter that splits input in differently sized sstables
 *
 * Biggest sstable will be total_compaction_size / 2, second biggest total_compaction_size / 4 etc until
 * the result would be sub 50MB, all those are put in the same
 */
public class SplittingSizeTieredCompactionWriter extends CompactionAwareWriter
{
    private static final Logger logger = LoggerFactory.getLogger(SplittingSizeTieredCompactionWriter.class);

    public static final long DEFAULT_SMALLEST_SSTABLE_BYTES = 50_000_000;
    private final double[] ratios;
    private final long totalSize;
    private final Set<SSTableReader> allSSTables;
    private long currentBytesToWrite;
    private int currentRatioIndex = 0;

    public SplittingSizeTieredCompactionWriter(CompactionRealm realm, Directories directories, LifecycleTransaction txn, Set<SSTableReader> nonExpiredSSTables)
    {
        this(realm, directories, txn, nonExpiredSSTables, DEFAULT_SMALLEST_SSTABLE_BYTES);
    }

    public SplittingSizeTieredCompactionWriter(CompactionRealm realm, Directories directories, LifecycleTransaction txn, Set<SSTableReader> nonExpiredSSTables, long smallestSSTable)
    {
        super(realm, directories, txn, nonExpiredSSTables, false);
        this.allSSTables = txn.originals();
        totalSize = realm.getExpectedCompactedFileSize(nonExpiredSSTables, txn.opType());
        double[] potentialRatios = new double[20];
        double currentRatio = 1;
        for (int i = 0; i < potentialRatios.length; i++)
        {
            currentRatio /= 2;
            potentialRatios[i] = currentRatio;
        }

        int noPointIndex = 0;
        // find how many sstables we should create - 50MB min sstable size
        for (double ratio : potentialRatios)
        {
            noPointIndex++;
            if (ratio * totalSize < smallestSSTable)
            {
                break;
            }
        }
        ratios = Arrays.copyOfRange(potentialRatios, 0, noPointIndex);
        currentBytesToWrite = Math.round(totalSize * ratios[currentRatioIndex]);
    }

    @Override
    protected boolean shouldSwitchWriterInCurrentLocation(DecoratedKey key)
    {
        if (sstableWriter.currentWriter().getEstimatedOnDiskBytesWritten() > currentBytesToWrite && currentRatioIndex < ratios.length - 1) // if we underestimate how many keys we have, the last sstable might get more than we expect
        {
            currentRatioIndex++;
            currentBytesToWrite = getExpectedWriteSize();
            logger.debug("Switching writer, currentBytesToWrite = {}", currentBytesToWrite);
            return true;
        }
        return false;
    }

    @Override
    protected SSTableWriter sstableWriter(Directories.DataDirectory directory, Token diskBoundary)
    {
        long currentPartitionsToWrite = Math.round(ratios[currentRatioIndex] * estimatedTotalKeys);
        logger.trace("Switching writer, currentPartitionsToWrite = {}", currentPartitionsToWrite);

        return SSTableWriter.create(realm.newSSTableDescriptor(getDirectories().getLocationForDisk(directory)),
                                    currentPartitionsToWrite,
                                    minRepairedAt,
                                    pendingRepair,
                                    isTransient,
                                    realm.metadataRef(),
                                    new MetadataCollector(allSSTables, realm.metadata().comparator, 0),
                                    SerializationHeader.make(realm.metadata(), nonExpiredSSTables),
                                    realm.getIndexManager().listIndexGroups(),
                                    txn);
    }

    @Override
    protected long getExpectedWriteSize()
    {
        return Math.round(totalSize * ratios[currentRatioIndex]);
    }
}
