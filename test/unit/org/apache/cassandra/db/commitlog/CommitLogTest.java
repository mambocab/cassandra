/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.apache.cassandra.db.commitlog;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import com.google.common.collect.Iterables;

import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.Util;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.ParameterizedClass;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.Mutation;
import org.apache.cassandra.db.RowUpdateBuilder;
import org.apache.cassandra.db.commitlog.CommitLogReplayer.CommitLogReplayException;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.marshal.AsciiType;
import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.db.partitions.PartitionUpdate;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.io.compress.DeflateCompressor;
import org.apache.cassandra.io.compress.LZ4Compressor;
import org.apache.cassandra.io.compress.SnappyCompressor;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.schema.KeyspaceParams;
import org.apache.cassandra.security.EncryptionContext;
import org.apache.cassandra.security.EncryptionContextGenerator;
import org.apache.cassandra.utils.Hex;
import org.apache.cassandra.utils.JVMStabilityInspector;
import org.apache.cassandra.utils.KillerForTests;
import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.utils.vint.VIntCoding;

import org.junit.After;
import static org.apache.cassandra.utils.ByteBufferUtil.bytes;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class CommitLogTest
{
    private static final String KEYSPACE1 = "CommitLogTest";
    private static final String KEYSPACE2 = "CommitLogTestNonDurable";
    private static final String STANDARD1 = "Standard1";
    private static final String STANDARD2 = "Standard2";

    private static JVMStabilityInspector.Killer oldKiller;
    private static KillerForTests testKiller;
    private final CommitLog commitLog = CommitLog.instance;

    public CommitLogTest(ParameterizedClass commitLogCompression, EncryptionContext encryptionContext)
    {
        DatabaseDescriptor.setCommitLogCompression(commitLogCompression);
        DatabaseDescriptor.setEncryptionContext(encryptionContext);
    }

    @Parameters()
    public static Collection<Object[]> generateData()
    {
        return Arrays.asList(new Object[][]{
            {null, EncryptionContextGenerator.createDisabledContext()}, // No compression, no encryption
            {null, EncryptionContextGenerator.createContext(true)}, // Encryption
            {new ParameterizedClass(LZ4Compressor.class.getName(), Collections.emptyMap()), EncryptionContextGenerator.createDisabledContext()},
            {new ParameterizedClass(SnappyCompressor.class.getName(), Collections.emptyMap()), EncryptionContextGenerator.createDisabledContext()},
            {new ParameterizedClass(DeflateCompressor.class.getName(), Collections.emptyMap()), EncryptionContextGenerator.createDisabledContext()}});
    }

    @BeforeClass
    public static void beforeClass() throws ConfigurationException
    {
        SchemaLoader.prepareServer();
        SchemaLoader.createKeyspace(KEYSPACE1,
                                    KeyspaceParams.simple(1),
                                    SchemaLoader.standardCFMD(KEYSPACE1, STANDARD1, 0, AsciiType.instance, BytesType.instance),
                                    SchemaLoader.standardCFMD(KEYSPACE1, STANDARD2, 0, AsciiType.instance, BytesType.instance));
        SchemaLoader.createKeyspace(KEYSPACE2,
                                    KeyspaceParams.simpleTransient(1),
                                    SchemaLoader.standardCFMD(KEYSPACE1, STANDARD1, 0, AsciiType.instance, BytesType.instance),
                                    SchemaLoader.standardCFMD(KEYSPACE1, STANDARD2, 0, AsciiType.instance, BytesType.instance));
        CompactionManager.instance.disableAutoCompaction();

        testKiller = new KillerForTests();

        // While we don't want the JVM to be nuked from under us on a test failure, we DO want some indication of
        // an error. If we hit a "Kill the JVM" condition while working with the CL when we don't expect it, an aggressive
        // KillerForTests will assertion out on us.
        oldKiller = JVMStabilityInspector.replaceKiller(testKiller);
    }

    @AfterClass
    public static void afterClass()
    {
        JVMStabilityInspector.replaceKiller(oldKiller);
    }

    @Before
    public void beforeTest() throws IOException
    {
        commitLog.resetUnsafe(true);
    }

    @After
    public void afterTest()
    {
        testKiller.reset();
    }

    @Test
    public void testRecoveryWithEmptyLog() throws Exception
    {
        runExpecting(() -> {
            commitLog.recoverFiles(tmpFile(CommitLogDescriptor.current_version));
            return null;
        }, CommitLogReplayException.class);
    }

    @Test
    public void testRecoveryWithEmptyLog20() throws Exception
    {
        commitLog.recoverFiles(tmpFile(CommitLogDescriptor.VERSION_20));
    }

    @Test
    public void testRecoveryWithZeroLog() throws Exception
    {
        testRecovery(new byte[10], null);
    }

    @Test
    public void testRecoveryWithShortLog() throws Exception
    {
        // force EOF while reading log
        testRecoveryWithBadSizeArgument(100, 10);
    }

    @Test
    public void testRecoveryWithShortSize() throws Exception
    {
        runExpecting(() -> {
            testRecovery(new byte[2], CommitLogDescriptor.VERSION_20);
            return null;
        }, CommitLogReplayException.class);
    }

    @Test
    public void testRecoveryWithShortMutationSize() throws Exception
    {
        testRecoveryWithBadSizeArgument(9, 10);
    }

    private void testRecoveryWithGarbageLog() throws Exception
    {
        byte[] garbage = new byte[100];
        (new java.util.Random()).nextBytes(garbage);
        testRecovery(garbage, CommitLogDescriptor.current_version);
    }

    @Test
    public void testRecoveryWithGarbageLog_fail() throws Exception
    {
        runExpecting(() -> {
            testRecoveryWithGarbageLog();
            return null;
        }, CommitLogReplayException.class);
    }

    @Test
    public void testRecoveryWithGarbageLog_ignoredByProperty() throws Exception
    {
        try {
            System.setProperty(CommitLogReplayer.IGNORE_REPLAY_ERRORS_PROPERTY, "true");
            testRecoveryWithGarbageLog();
        } finally {
            System.clearProperty(CommitLogReplayer.IGNORE_REPLAY_ERRORS_PROPERTY);
        }
    }

    @Test
    public void testRecoveryWithBadSizeChecksum() throws Exception
    {
        Checksum checksum = new CRC32();
        checksum.update(100);
        testRecoveryWithBadSizeArgument(100, 100, ~checksum.getValue());
    }

    @Test
    public void testRecoveryWithNegativeSizeArgument() throws Exception
    {
        // garbage from a partial/bad flush could be read as a negative size even if there is no EOF
        testRecoveryWithBadSizeArgument(-10, 10); // negative size, but no EOF
    }

    @Test
    public void testDontDeleteIfDirty() throws Exception
    {
        Keyspace ks = Keyspace.open(KEYSPACE1);
        ColumnFamilyStore cfs1 = ks.getColumnFamilyStore(STANDARD1);
        ColumnFamilyStore cfs2 = ks.getColumnFamilyStore(STANDARD2);

        // Roughly 32 MB mutation
        Mutation m = new RowUpdateBuilder(cfs1.metadata, 0, "k")
                     .clustering("bytes")
                     .add("val", ByteBuffer.allocate(DatabaseDescriptor.getCommitLogSegmentSize() / 4))
                     .build();

        // Adding it 5 times
        commitLog.add(m);
        commitLog.add(m);
        commitLog.add(m);
        commitLog.add(m);
        commitLog.add(m);

        // Adding new mutation on another CF
        Mutation m2 = new RowUpdateBuilder(cfs2.metadata, 0, "k")
                      .clustering("bytes")
                      .add("val", ByteBuffer.allocate(4))
                      .build();
        commitLog.add(m2);

        assertEquals(2, commitLog.segmentManager.getActiveSegments().size());

        UUID cfid2 = m2.getColumnFamilyIds().iterator().next();
        commitLog.discardCompletedSegments(cfid2, commitLog.getCurrentPosition());

        // Assert we still have both our segments
        assertEquals(2, commitLog.segmentManager.getActiveSegments().size());
    }

    @Test
    public void testDeleteIfNotDirty() throws Exception
    {
        Keyspace ks = Keyspace.open(KEYSPACE1);
        ColumnFamilyStore cfs1 = ks.getColumnFamilyStore(STANDARD1);
        ColumnFamilyStore cfs2 = ks.getColumnFamilyStore(STANDARD2);

        // Roughly 32 MB mutation
         Mutation rm = new RowUpdateBuilder(cfs1.metadata, 0, "k")
                  .clustering("bytes")
                  .add("val", ByteBuffer.allocate((DatabaseDescriptor.getCommitLogSegmentSize()/4) - 1))
                  .build();

        // Adding it twice (won't change segment)
        commitLog.add(rm);
        commitLog.add(rm);

        assertEquals(1, commitLog.segmentManager.getActiveSegments().size());

        // "Flush": this won't delete anything
        UUID cfid1 = rm.getColumnFamilyIds().iterator().next();
        commitLog.sync(true);
        commitLog.discardCompletedSegments(cfid1, commitLog.getCurrentPosition());

        assertEquals(1, commitLog.segmentManager.getActiveSegments().size());

        // Adding new mutation on another CF, large enough (including CL entry overhead) that a new segment is created
        Mutation rm2 = new RowUpdateBuilder(cfs2.metadata, 0, "k")
                       .clustering("bytes")
                       .add("val", ByteBuffer.allocate(DatabaseDescriptor.getMaxMutationSize() - 200))
                       .build();
        commitLog.add(rm2);
        // also forces a new segment, since each entry-with-overhead is just under half the CL size
        commitLog.add(rm2);
        commitLog.add(rm2);

        assertEquals(3, commitLog.segmentManager.getActiveSegments().size());

        // "Flush" second cf: The first segment should be deleted since we
        // didn't write anything on cf1 since last flush (and we flush cf2)

        UUID cfid2 = rm2.getColumnFamilyIds().iterator().next();
        commitLog.discardCompletedSegments(cfid2, commitLog.getCurrentPosition());

        // Assert we still have both our segment
        assertEquals(1, commitLog.segmentManager.getActiveSegments().size());
    }

    private static int getMaxRecordDataSize(String keyspace, ByteBuffer key, String cfName, String colName)
    {
        ColumnFamilyStore cfs = Keyspace.open(keyspace).getColumnFamilyStore(cfName);
        // We don't want to allocate a size of 0 as this is optimized under the hood and our computation would
        // break testEqualRecordLimit
        int allocSize = 1;
        Mutation rm = new RowUpdateBuilder(cfs.metadata, 0, key)
                      .clustering(colName)
                      .add("val", ByteBuffer.allocate(allocSize)).build();

        int max = DatabaseDescriptor.getMaxMutationSize();
        max -= CommitLogSegment.ENTRY_OVERHEAD_SIZE; // log entry overhead

        // Note that the size of the value if vint encoded. So we first compute the ovehead of the mutation without the value and it's size
        int mutationOverhead = (int)Mutation.serializer.serializedSize(rm, MessagingService.current_version) - (VIntCoding.computeVIntSize(allocSize) + allocSize);
        max -= mutationOverhead;

        // Now, max is the max for both the value and it's size. But we want to know how much we can allocate, i.e. the size of the value.
        int sizeOfMax = VIntCoding.computeVIntSize(max);
        max -= sizeOfMax;
        assert VIntCoding.computeVIntSize(max) == sizeOfMax; // sanity check that we're still encoded with the size we though we would
        return max;
    }

    private static int getMaxRecordDataSize()
    {
        return getMaxRecordDataSize(KEYSPACE1, bytes("k"), STANDARD1, "bytes");
    }

    // CASSANDRA-3615
    @Test
    public void testEqualRecordLimit() throws Exception
    {
        ColumnFamilyStore cfs = Keyspace.open(KEYSPACE1).getColumnFamilyStore(STANDARD1);
        Mutation rm = new RowUpdateBuilder(cfs.metadata, 0, "k")
                      .clustering("bytes")
                      .add("val", ByteBuffer.allocate(getMaxRecordDataSize()))
                      .build();
        commitLog.add(rm);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testExceedRecordLimit() throws Exception
    {
        Keyspace ks = Keyspace.open(KEYSPACE1);
        ColumnFamilyStore cfs = ks.getColumnFamilyStore(STANDARD1);
        Mutation rm = new RowUpdateBuilder(cfs.metadata, 0, "k")
                      .clustering("bytes")
                      .add("val", ByteBuffer.allocate(1 + getMaxRecordDataSize()))
                      .build();
        commitLog.add(rm);
        throw new AssertionError("mutation larger than limit was accepted");
    }

    protected void testRecoveryWithBadSizeArgument(int size, int dataSize) throws Exception
    {
        Checksum checksum = new CRC32();
        checksum.update(size);
        testRecoveryWithBadSizeArgument(size, dataSize, checksum.getValue());
    }

    protected void testRecoveryWithBadSizeArgument(int size, int dataSize, long checksum) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(out);
        dout.writeInt(size);
        dout.writeLong(checksum);
        dout.write(new byte[dataSize]);
        dout.close();
        testRecovery(out.toByteArray(), CommitLogReplayException.class);
    }

    /**
     * Create a temporary commit log file with an appropriate descriptor at the head.
     *
     * @return the commit log file reference and the first position after the descriptor in the file
     * (so that subsequent writes happen at the correct file location).
     */
    protected Pair<File, Integer> tmpFile() throws IOException
    {
        EncryptionContext encryptionContext = DatabaseDescriptor.getEncryptionContext();
        CommitLogDescriptor desc = new CommitLogDescriptor(CommitLogDescriptor.current_version,
                                                           CommitLogSegment.getNextId(),
                                                           DatabaseDescriptor.getCommitLogCompression(),
                                                           encryptionContext);


        ByteBuffer buf = ByteBuffer.allocate(1024);
        CommitLogDescriptor.writeHeader(buf, desc, getAdditionalHeaders(encryptionContext));
        buf.flip();
        int positionAfterHeader = buf.limit() + 1;

        File logFile = new File(DatabaseDescriptor.getCommitLogLocation(), desc.fileName());

        try (OutputStream lout = new FileOutputStream(logFile))
        {
            lout.write(buf.array(), 0, buf.limit());
        }

        return Pair.create(logFile, positionAfterHeader);
    }

    private Map<String, String> getAdditionalHeaders(EncryptionContext encryptionContext)
    {
        if (!encryptionContext.isEnabled())
            return Collections.emptyMap();

        // if we're testing encryption, we need to write out a cipher IV to the descriptor headers
        byte[] buf = new byte[16];
        new Random().nextBytes(buf);
        return Collections.singletonMap(EncryptionContext.ENCRYPTION_IV, Hex.bytesToHex(buf));
    }

    protected File tmpFile(int version) throws IOException
    {
        File logFile = File.createTempFile("CommitLog-" + version + "-", ".log");
        assert logFile.length() == 0;
        return logFile;
    }

    protected Void testRecovery(byte[] logData, int version) throws Exception
    {
        File logFile = tmpFile(version);
        try (OutputStream lout = new FileOutputStream(logFile))
        {
            lout.write(logData);
            //statics make it annoying to test things correctly
            commitLog.recover(logFile.getPath()); //CASSANDRA-1119 / CASSANDRA-1179 throw on failure*/
        }
        return null;
    }

    protected Void testRecovery(CommitLogDescriptor desc, byte[] logData) throws Exception
    {
        File logFile = tmpFile(desc.version);
        CommitLogDescriptor fromFile = CommitLogDescriptor.fromFileName(logFile.getName());
        // Change id to match file.
        desc = new CommitLogDescriptor(desc.version, fromFile.id, desc.compression, desc.getEncryptionContext());
        ByteBuffer buf = ByteBuffer.allocate(1024);
        CommitLogDescriptor.writeHeader(buf, desc, getAdditionalHeaders(desc.getEncryptionContext()));
        try (OutputStream lout = new FileOutputStream(logFile))
        {
            lout.write(buf.array(), 0, buf.position());
            lout.write(logData);
            //statics make it annoying to test things correctly
            commitLog.recover(logFile.getPath()); //CASSANDRA-1119 / CASSANDRA-1179 throw on failure*/
        }
        return null;
    }

    @Test
    public void testRecoveryWithIdMismatch() throws Exception
    {
        CommitLogDescriptor desc = new CommitLogDescriptor(4, null, EncryptionContextGenerator.createDisabledContext());
        File logFile = tmpFile(desc.version);
        ByteBuffer buf = ByteBuffer.allocate(1024);
        CommitLogDescriptor.writeHeader(buf, desc);
        try (OutputStream lout = new FileOutputStream(logFile))
        {
            lout.write(buf.array(), 0, buf.position());

            runExpecting(() -> {
                commitLog.recover(logFile.getPath()); //CASSANDRA-1119 / CASSANDRA-1179 throw on failure*/
                return null;
            }, CommitLogReplayException.class);
        }
    }

    @Test
    public void testRecoveryWithBadCompressor() throws Exception
    {
        CommitLogDescriptor desc = new CommitLogDescriptor(4, new ParameterizedClass("UnknownCompressor", null), EncryptionContextGenerator.createDisabledContext());
        runExpecting(() -> {
            testRecovery(desc, new byte[0]);
            return null;
        }, CommitLogReplayException.class);
    }

    protected void runExpecting(Callable<Void> r, Class<?> expected)
    {
        Throwable caught = null;
        try
        {
            r.call();
        }
        catch (Throwable t)
        {
            if (expected != t.getClass())
                throw new AssertionError("Expected exception " + expected + ", got " + t, t);
            caught = t;
        }
        if (expected != null && caught == null)
            Assert.fail("Expected exception " + expected + " but call completed successfully.");

        assertEquals("JVM kill state doesn't match expectation.", expected != null, testKiller.wasKilled());
    }

    protected void testRecovery(final byte[] logData, Class<?> expected) throws Exception
    {
        ParameterizedClass commitLogCompression = DatabaseDescriptor.getCommitLogCompression();
        EncryptionContext encryptionContext = DatabaseDescriptor.getEncryptionContext();
        runExpecting(() -> testRecovery(logData, CommitLogDescriptor.VERSION_20), expected);
        runExpecting(() -> testRecovery(new CommitLogDescriptor(4, commitLogCompression, encryptionContext), logData), expected);
    }

    protected void testRecovery(byte[] logData) throws Exception
    {
        Pair<File, Integer> pair = tmpFile();
        try (RandomAccessFile raf = new RandomAccessFile(pair.left, "rw"))
        {
            raf.seek(pair.right);
            raf.write(logData);
            raf.close();

            commitLog.recoverFiles(pair.left); //CASSANDRA-1119 / CASSANDRA-1179 throw on failure*/
        }
    }

    @Test
    public void testTruncateWithoutSnapshot() throws ExecutionException, InterruptedException, IOException
    {
        boolean originalState = DatabaseDescriptor.isAutoSnapshot();
        try
        {
            boolean prev = DatabaseDescriptor.isAutoSnapshot();
            DatabaseDescriptor.setAutoSnapshot(false);
            Keyspace ks = Keyspace.open(KEYSPACE1);
            ColumnFamilyStore cfs1 = ks.getColumnFamilyStore(STANDARD1);
            ColumnFamilyStore cfs2 = ks.getColumnFamilyStore(STANDARD2);

            new RowUpdateBuilder(cfs1.metadata, 0, "k").clustering("bytes").add("val", ByteBuffer.allocate(100)).build().applyUnsafe();
            cfs1.truncateBlocking();
            DatabaseDescriptor.setAutoSnapshot(prev);
            Mutation m2 = new RowUpdateBuilder(cfs2.metadata, 0, "k")
                          .clustering("bytes")
                          .add("val", ByteBuffer.allocate(DatabaseDescriptor.getCommitLogSegmentSize() / 4))
                          .build();

            for (int i = 0 ; i < 5 ; i++)
                commitLog.add(m2);

            assertEquals(2, commitLog.segmentManager.getActiveSegments().size());
            CommitLogPosition position = commitLog.getCurrentPosition();
            for (Keyspace keyspace : Keyspace.system())
                for (ColumnFamilyStore syscfs : keyspace.getColumnFamilyStores())
                    commitLog.discardCompletedSegments(syscfs.metadata.cfId, position);
            commitLog.discardCompletedSegments(cfs2.metadata.cfId, position);
            assertEquals(1, commitLog.segmentManager.getActiveSegments().size());
        }
        finally
        {
            DatabaseDescriptor.setAutoSnapshot(originalState);
        }
    }

    @Test
    public void testTruncateWithoutSnapshotNonDurable() throws IOException
    {
        boolean originalState = DatabaseDescriptor.getAutoSnapshot();
        try
        {
            DatabaseDescriptor.setAutoSnapshot(false);
            Keyspace notDurableKs = Keyspace.open(KEYSPACE2);
            Assert.assertFalse(notDurableKs.getMetadata().params.durableWrites);

            ColumnFamilyStore cfs = notDurableKs.getColumnFamilyStore("Standard1");
            new RowUpdateBuilder(cfs.metadata, 0, "key1")
            .clustering("bytes").add("val", bytes("abcd"))
            .build()
            .applyUnsafe();

            assertTrue(Util.getOnlyRow(Util.cmd(cfs).columns("val").build())
                           .cells().iterator().next().value().equals(bytes("abcd")));

            cfs.truncateBlocking();

            Util.assertEmpty(Util.cmd(cfs).columns("val").build());
        }
        finally
        {
            DatabaseDescriptor.setAutoSnapshot(originalState);
        }
    }

    @Test
    public void replaySimple() throws IOException
    {
        int cellCount = 0;
        ColumnFamilyStore cfs = Keyspace.open(KEYSPACE1).getColumnFamilyStore(STANDARD1);
        final Mutation rm1 = new RowUpdateBuilder(cfs.metadata, 0, "k1")
                             .clustering("bytes")
                             .add("val", bytes("this is a string"))
                             .build();
        cellCount += 1;
        commitLog.add(rm1);

        final Mutation rm2 = new RowUpdateBuilder(cfs.metadata, 0, "k2")
                             .clustering("bytes")
                             .add("val", bytes("this is a string"))
                             .build();
        cellCount += 1;
        commitLog.add(rm2);

        commitLog.sync(true);

        SimpleCountingReplayer replayer = new SimpleCountingReplayer(commitLog, CommitLogPosition.NONE);
        List<String> activeSegments = commitLog.getActiveSegmentNames();
        Assert.assertFalse(activeSegments.isEmpty());

        File[] files = new File(commitLog.segmentManager.storageDirectory).listFiles((file, name) -> activeSegments.contains(name));
        replayer.replayFiles(files);

        assertEquals(cellCount, replayer.cells);
    }

    @Test
    public void replayWithDiscard() throws IOException
    {
        int cellCount = 0;
        int max = 1024;
        int discardPosition = (int)(max * .8); // an arbitrary number of entries that we'll skip on the replay
        CommitLogPosition commitLogPosition = null;
        ColumnFamilyStore cfs = Keyspace.open(KEYSPACE1).getColumnFamilyStore(STANDARD1);

        for (int i = 0; i < max; i++)
        {
            final Mutation rm1 = new RowUpdateBuilder(cfs.metadata, 0, "k" + 1)
                                 .clustering("bytes")
                                 .add("val", bytes("this is a string"))
                                 .build();
            CommitLogPosition position = commitLog.add(rm1);

            if (i == discardPosition)
                commitLogPosition = position;
            if (i > discardPosition)
            {
                cellCount += 1;
            }
        }

        commitLog.sync(true);

        SimpleCountingReplayer replayer = new SimpleCountingReplayer(commitLog, commitLogPosition);
        List<String> activeSegments = commitLog.getActiveSegmentNames();
        Assert.assertFalse(activeSegments.isEmpty());

        File[] files = new File(commitLog.segmentManager.storageDirectory).listFiles((file, name) -> activeSegments.contains(name));
        replayer.replayFiles(files);

        assertEquals(cellCount, replayer.cells);
    }

    class SimpleCountingReplayer extends CommitLogReplayer
    {
        private final CommitLogPosition filterPosition;
        private CommitLogReader reader;
        int cells;
        int skipped;

        SimpleCountingReplayer(CommitLog commitLog, CommitLogPosition filterPosition)
        {
            super(commitLog, filterPosition, Collections.emptyMap(), ReplayFilter.create());
            this.filterPosition = filterPosition;
            this.reader = new CommitLogReader();
        }

        @SuppressWarnings("resource")
        @Override
        public void handleMutation(Mutation m, int size, int entryLocation, CommitLogDescriptor desc)
        {
            if (entryLocation <= filterPosition.position)
            {
                // Skip over this mutation.
                skipped++;
                return;
            }
            for (PartitionUpdate partitionUpdate : m.getPartitionUpdates())
                for (Row row : partitionUpdate)
                    cells += Iterables.size(row.cells());
        }
    }
}

