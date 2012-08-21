package com.thinkaurelius.faunus.formats.titan;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;
import com.thinkaurelius.faunus.FaunusVertex;
import org.apache.cassandra.auth.IAuthenticator;
import org.apache.cassandra.config.ConfigurationException;
import org.apache.cassandra.db.IColumn;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.TypeParser;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.hadoop.ColumnFamilySplit;
import org.apache.cassandra.hadoop.ConfigHelper;
import org.apache.cassandra.thrift.AuthenticationRequest;
import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.CfDef;
import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ColumnOrSuperColumn;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.CounterColumn;
import org.apache.cassandra.thrift.CounterSuperColumn;
import org.apache.cassandra.thrift.IndexExpression;
import org.apache.cassandra.thrift.KeyRange;
import org.apache.cassandra.thrift.KeySlice;
import org.apache.cassandra.thrift.KsDef;
import org.apache.cassandra.thrift.SlicePredicate;
import org.apache.cassandra.thrift.SuperColumn;
import org.apache.cassandra.thrift.TBinaryProtocol;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.Pair;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public class TitanCassandraRecordReader extends RecordReader<NullWritable, FaunusVertex> {
    public static final int CASSANDRA_HADOOP_MAX_KEY_SIZE_DEFAULT = 8;

    private static final Logger logger = LoggerFactory.getLogger(TitanCassandraRecordReader.class);

    private ColumnFamilySplit split;
    private RowIterator iter;
    private FaunusVertex currentRow;
    private SlicePredicate predicate;
    private boolean isEmptyPredicate;
    private int totalRowCount; // total number of rows to fetch
    private int batchSize; // fetch this many per batch
    private String cfName;
    private String keyspace;
    private TSocket socket;
    private Cassandra.Client client;
    private ConsistencyLevel consistencyLevel;
    private List<IndexExpression> filter;

    private final int keyBufferSize;
    private final FaunusTitanGraph graph;

    public TitanCassandraRecordReader(FaunusTitanGraph graph) {
        this(graph,CASSANDRA_HADOOP_MAX_KEY_SIZE_DEFAULT);
    }

    public TitanCassandraRecordReader(FaunusTitanGraph graph, int keyBufferSize) {
        super();
        if (graph==null) throw new IllegalArgumentException("Graph cannot be null");
        this.keyBufferSize = keyBufferSize;
        this.graph = graph;
    }

    public void close() {
        if (socket != null && socket.isOpen()) {
            socket.close();
            socket = null;
            client = null;
        }
    }

    public NullWritable getCurrentKey() {
        return NullWritable.get();
    }

    public FaunusVertex getCurrentValue() {
        return currentRow;
    }

    public float getProgress() {
        // TODO this is totally broken for wide rows
        // the progress is likely to be reported slightly off the actual but close enough
        return ((float) iter.rowsRead()) / totalRowCount;
    }

    static boolean isEmptyPredicate(SlicePredicate predicate) {
        if (predicate == null)
            return true;

        if (predicate.isSetColumn_names() && predicate.getSlice_range() == null)
            return false;

        if (predicate.getSlice_range() == null)
            return true;

        byte[] start = predicate.getSlice_range().getStart();
        if ((start != null) && (start.length > 0))
            return false;

        byte[] finish = predicate.getSlice_range().getFinish();
        if ((finish != null) && (finish.length > 0))
            return false;

        return true;
    }

    public void initialize(InputSplit split, TaskAttemptContext context) throws IOException {
        this.split = (ColumnFamilySplit) split;
        Configuration conf = context.getConfiguration();
        KeyRange jobRange = ConfigHelper.getInputKeyRange(conf);
        filter = jobRange == null ? null : jobRange.row_filter;
        predicate = ConfigHelper.getInputSlicePredicate(conf);
        boolean widerows = ConfigHelper.getInputIsWide(conf);
        isEmptyPredicate = isEmptyPredicate(predicate);
        totalRowCount = ConfigHelper.getInputSplitSize(conf);
        batchSize = ConfigHelper.getRangeBatchSize(conf);
        cfName = ConfigHelper.getInputColumnFamily(conf);
        consistencyLevel = ConsistencyLevel.valueOf(ConfigHelper.getReadConsistencyLevel(conf));


        keyspace = ConfigHelper.getInputKeyspace(conf);

        try {
            // only need to connect once
            if (socket != null && socket.isOpen())
                return;

            // create connection using thrift
            String location = getLocation();
            socket = new TSocket(location, ConfigHelper.getInputRpcPort(conf));
            TBinaryProtocol binaryProtocol = new TBinaryProtocol(new TFramedTransport(socket));
            client = new Cassandra.Client(binaryProtocol);
            socket.open();

            // log in
            client.set_keyspace(keyspace);
            if (ConfigHelper.getInputKeyspaceUserName(conf) != null) {
                Map<String, String> creds = new HashMap<String, String>();
                creds.put(IAuthenticator.USERNAME_KEY, ConfigHelper.getInputKeyspaceUserName(conf));
                creds.put(IAuthenticator.PASSWORD_KEY, ConfigHelper.getInputKeyspacePassword(conf));
                AuthenticationRequest authRequest = new AuthenticationRequest(creds);
                client.login(authRequest);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        iter = widerows ? new WideRowIterator() : new StaticRowIterator();
        logger.debug("created {}", iter);
    }


    public boolean nextKeyValue() throws IOException {
        if (!iter.hasNext())
            return false;
        currentRow = graph.readFaunusVertex(iter.next());
        return true;
    }

    private String getLocation() {
        ArrayList<InetAddress> localAddresses = new ArrayList<InetAddress>();
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements())
                localAddresses.addAll(Collections.list(nets.nextElement().getInetAddresses()));
        } catch (SocketException e) {
            throw new AssertionError(e);
        }

        for (InetAddress address : localAddresses) {
            for (String location : split.getLocations()) {
                InetAddress locationAddress = null;
                try {
                    locationAddress = InetAddress.getByName(location);
                } catch (UnknownHostException e) {
                    throw new AssertionError(e);
                }
                if (address.equals(locationAddress)) {
                    return location;
                }
            }
        }
        return split.getLocations()[0];
    }

    private abstract class RowIterator extends AbstractIterator<Pair<ByteBuffer, SortedMap<ByteBuffer, IColumn>>> {
        protected List<KeySlice> rows;
        protected int totalRead = 0;
        protected final AbstractType<?> comparator;
        protected final AbstractType<?> subComparator;
        protected final IPartitioner partitioner;

        private RowIterator() {
            try {
                partitioner = FBUtilities.newPartitioner(client.describe_partitioner());

                // Get the Keyspace metadata, then get the specific CF metadata
                // in order to populate the sub/comparator.
                KsDef ks_def = client.describe_keyspace(keyspace);
                List<String> cfnames = new ArrayList<String>();
                for (CfDef cfd : ks_def.cf_defs)
                    cfnames.add(cfd.name);
                int idx = cfnames.indexOf(cfName);
                CfDef cf_def = ks_def.cf_defs.get(idx);

                comparator = TypeParser.parse(cf_def.comparator_type);
                subComparator = cf_def.subcomparator_type == null ? null : TypeParser.parse(cf_def.subcomparator_type);
            } catch (ConfigurationException e) {
                throw new RuntimeException("unable to load sub/comparator", e);
            } catch (TException e) {
                throw new RuntimeException("error communicating via Thrift", e);
            } catch (Exception e) {
                throw new RuntimeException("unable to load keyspace " + keyspace, e);
            }
        }

        /**
         * @return total number of rows read by this record reader
         */
        public int rowsRead() {
            return totalRead;
        }

        protected IColumn unthriftify(ColumnOrSuperColumn cosc) {
            if (cosc.counter_column != null)
                return unthriftifyCounter(cosc.counter_column);
            if (cosc.counter_super_column != null)
                return unthriftifySuperCounter(cosc.counter_super_column);
            if (cosc.super_column != null)
                return unthriftifySuper(cosc.super_column);
            assert cosc.column != null;
            return unthriftifySimple(cosc.column);
        }

        private IColumn unthriftifySuper(SuperColumn super_column) {
            org.apache.cassandra.db.SuperColumn sc = new org.apache.cassandra.db.SuperColumn(super_column.name, subComparator);
            for (Column column : super_column.columns) {
                sc.addColumn(unthriftifySimple(column));
            }
            return sc;
        }

        protected IColumn unthriftifySimple(Column column) {
            return new org.apache.cassandra.db.Column(column.name, column.value, column.timestamp);
        }

        private IColumn unthriftifyCounter(CounterColumn column) {
            //CounterColumns read the nodeID from the System table, so need the StorageService running and access
            //to cassandra.yaml. To avoid a Hadoop needing access to yaml return a regular Column.
            return new org.apache.cassandra.db.Column(column.name, ByteBufferUtil.bytes(column.value), 0);
        }

        private IColumn unthriftifySuperCounter(CounterSuperColumn superColumn) {
            org.apache.cassandra.db.SuperColumn sc = new org.apache.cassandra.db.SuperColumn(superColumn.name, subComparator);
            for (CounterColumn column : superColumn.columns)
                sc.addColumn(unthriftifyCounter(column));
            return sc;
        }
    }

    private class StaticRowIterator extends RowIterator {
        protected int i = 0;

        private void maybeInit() {
            // check if we need another batch
            if (rows != null && i < rows.size())
                return;

            String startToken;
            if (totalRead == 0) {
                // first request
                startToken = split.getStartToken();
            } else {
                startToken = partitioner.getTokenFactory().toString(partitioner.getToken(Iterables.getLast(rows).key));
                if (startToken.equals(split.getEndToken())) {
                    // reached end of the split
                    rows = null;
                    return;
                }
            }

            KeyRange keyRange = new KeyRange(batchSize)
                    .setStart_token(startToken)
                    .setEnd_token(split.getEndToken())
                    .setRow_filter(filter);
            try {
                rows = client.get_range_slices(new ColumnParent(cfName), predicate, keyRange, consistencyLevel);

                // nothing new? reached the end
                if (rows.isEmpty()) {
                    rows = null;
                    return;
                }

                // remove ghosts when fetching all columns
                if (isEmptyPredicate) {
                    Iterator<KeySlice> it = rows.iterator();
                    while (it.hasNext()) {
                        KeySlice ks = it.next();
                        if (ks.getColumnsSize() == 0) {
                            it.remove();
                        }
                    }

                    // all ghosts, spooky
                    if (rows.isEmpty()) {
                        maybeInit();
                        return;
                    }
                }

                // reset to iterate through this new batch
                i = 0;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        protected Pair<ByteBuffer, SortedMap<ByteBuffer, IColumn>> computeNext() {
            maybeInit();
            if (rows == null)
                return endOfData();

            totalRead++;
            KeySlice ks = rows.get(i++);
            SortedMap<ByteBuffer, IColumn> map = new TreeMap<ByteBuffer, IColumn>(comparator);
            for (ColumnOrSuperColumn cosc : ks.columns) {
                IColumn column = unthriftify(cosc);
                map.put(column.name(), column);
            }
            return new Pair<ByteBuffer, SortedMap<ByteBuffer, IColumn>>(ks.key, map);
        }
    }

    private class WideRowIterator extends RowIterator {
        private PeekingIterator<Pair<ByteBuffer, SortedMap<ByteBuffer, IColumn>>> wideColumns;
        private ByteBuffer lastColumn = ByteBufferUtil.EMPTY_BYTE_BUFFER;

        private void maybeInit() {
            if (wideColumns != null && wideColumns.hasNext())
                return;

            KeyRange keyRange;
            ByteBuffer startColumn;
            if (totalRead == 0) {
                String startToken = split.getStartToken();
                keyRange = new KeyRange(batchSize)
                        .setStart_token(startToken)
                        .setEnd_token(split.getEndToken())
                        .setRow_filter(filter);
            } else {
                KeySlice lastRow = Iterables.getLast(rows);
                logger.debug("Starting with last-seen row {}", lastRow.key);
                keyRange = new KeyRange(batchSize)
                        .setStart_key(lastRow.key)
                        .setEnd_token(split.getEndToken())
                        .setRow_filter(filter);
            }

            try {
                rows = client.get_paged_slice(cfName, keyRange, lastColumn, consistencyLevel);
                int n = 0;
                for (KeySlice row : rows)
                    n += row.columns.size();
                logger.debug("read {} columns in {} rows for {} starting with {}",
                        new Object[]{n, rows.size(), keyRange, lastColumn});

                wideColumns = Iterators.peekingIterator(new WideColumnIterator(rows));
                if (wideColumns.hasNext() && wideColumns.peek().right.keySet().iterator().next().equals(lastColumn))
                    wideColumns.next();
                if (!wideColumns.hasNext())
                    rows = null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        protected Pair<ByteBuffer, SortedMap<ByteBuffer, IColumn>> computeNext() {
            maybeInit();
            if (rows == null)
                return endOfData();

            totalRead++;
            Pair<ByteBuffer, SortedMap<ByteBuffer, IColumn>> next = wideColumns.next();
            lastColumn = next.right.values().iterator().next().name();
            return next;
        }

        private class WideColumnIterator extends AbstractIterator<Pair<ByteBuffer, SortedMap<ByteBuffer, IColumn>>> {
            private final Iterator<KeySlice> rows;
            private Iterator<ColumnOrSuperColumn> columns;
            public KeySlice currentRow;

            public WideColumnIterator(List<KeySlice> rows) {
                this.rows = rows.iterator();
                if (this.rows.hasNext())
                    nextRow();
                else
                    columns = Iterators.emptyIterator();
            }

            private void nextRow() {
                currentRow = rows.next();
                columns = currentRow.columns.iterator();
            }

            protected Pair<ByteBuffer, SortedMap<ByteBuffer, IColumn>> computeNext() {
                while (true) {
                    if (columns.hasNext()) {
                        ColumnOrSuperColumn cosc = columns.next();
                        ImmutableSortedMap<ByteBuffer, IColumn> map = ImmutableSortedMap.of(cosc.column.name, unthriftifySimple(cosc.column));
                        return Pair.<ByteBuffer, SortedMap<ByteBuffer, IColumn>>create(currentRow.key, map);
                    }

                    if (!rows.hasNext())
                        return endOfData();

                    nextRow();
                }
            }
        }
    }
}