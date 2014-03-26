package ca.mcgill.distsys.hbase96.indexcoprocessorsinmem.pluggableIndex.hashtableBased;

import ca.mcgill.distsys.hbase96.indexcommonsinmem.ByteUtil;
import ca.mcgill.distsys.hbase96.indexcommonsinmem.proto.Criterion;
import ca.mcgill.distsys.hbase96.indexcommonsinmem.proto.Range;
import ca.mcgill.distsys.hbase96.indexcoprocessorsinmem.pluggableIndex.AbstractPluggableIndex;
import ca.mcgill.distsys.hbase96.indexcoprocessorsinmem.pluggableIndex.commons.ByteArrayWrapper;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.NotServingRegionException;
import org.apache.hadoop.hbase.RegionTooBusyException;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.MultiVersionConsistencyControl;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// modified by Cong

public class RegionColumnIndex extends AbstractPluggableIndex implements
		Serializable {

	// private transient static Log LOG;

	/**
	 * 
	 */
	private static final long serialVersionUID = -1641091015504586661L;
	private HashMap<ByteArrayWrapper, RowIndex> rowIndexMap;
	private transient ReadWriteLock rwLock;
	private byte[] columnFamily;
	private byte[] qualifier;
	private int maxTreeSize;
	private String tableName;

	private void readObject(ObjectInputStream in) throws IOException,
			ClassNotFoundException {
		in.defaultReadObject();
		rwLock = new ReentrantReadWriteLock(true);
		// LOG = LogFactory.getLog(RegionColumnIndex.class);
	}

	public RegionColumnIndex(int maxTreeSize, byte[] columnFamily,
			byte[] qualifier) {
		// LOG = LogFactory.getLog(RegionColumnIndex.class);
		rowIndexMap = new HashMap<ByteArrayWrapper, RowIndex>(15000);
		rwLock = new ReentrantReadWriteLock(true);
		this.columnFamily = columnFamily;
		this.qualifier = qualifier;
		this.maxTreeSize = maxTreeSize;
	}

	public void add(byte[] key, byte[] value) {
		rwLock.writeLock().lock();

		try {
			internalAdd(key, Arrays.copyOf(value, value.length));
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			rwLock.writeLock().unlock();
		}
	}

	// Key is the column value we want to add, value is the primary key
	void internalAdd(byte[] key, byte[] value) throws IOException,
			ClassNotFoundException {
		RowIndex rowIndex;
		boolean newPKRefTree = false;
		ByteArrayWrapper keyByteArray = new ByteArrayWrapper(key);
		rowIndex = rowIndexMap.get(keyByteArray);
		if (rowIndex == null) {
			rowIndex = new RowIndex();
			newPKRefTree = true;
		}

		rowIndex.add(value, maxTreeSize);

		if (newPKRefTree) {
			rowIndexMap.put(keyByteArray, rowIndex);
		}
	}

	public byte[][] get(byte[] key) {
		return get(new ByteArrayWrapper(key));
	}

	public byte[][] get(ByteArrayWrapper key) {
		rwLock.readLock().lock();

		try {
			TreeSet<byte[]> pkRefs;

			byte[][] result = null;

			RowIndex rowIndex = rowIndexMap.get(key);

			if (rowIndex != null) {
				pkRefs = rowIndex.getPKRefs();
				if (pkRefs != null) {
					result = pkRefs.toArray(new byte[pkRefs.size()][]);
				}
			}
			return result;
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			rwLock.readLock().unlock();
		}
		return null;

	}

	public void fullBuild(HRegion region) {
		rwLock.writeLock().lock();

		try {
			Scan scan = new Scan();
			scan.addColumn(columnFamily, qualifier);
			scan.setCacheBlocks(false); // don't want to fill the cache
										// uselessly and create churn
			RegionScanner scanner = region.getScanner(scan);
			MultiVersionConsistencyControl.setThreadReadPoint(scanner
					.getMvccReadPoint());
			region.startRegionOperation();
			try {
				synchronized (scanner) {

					// Modified by Cong
					// List<KeyValue> values = new ArrayList<KeyValue>();
					List<Cell> values = new ArrayList<Cell>();
					rowIndexMap.clear();

					boolean more;
					do {

						more = scanner.nextRaw(values);
						if (!values.isEmpty() && values.get(0) != null
								&& values.get(0).getValue() != null) {
							if (values.get(0).getRow() == null) {
								// LOG.error("NULL ROW for VALUE [" +
								// values.get(0).getValue() +
								// "] in column [" + new
								// String(columnFamily) + ":"
								// + Bytes.toString(qualifier) + "]");
							} else {
								byte[] rowid = values.get(0).getRow();
								try {
									internalAdd(values.get(0).getValue(),
											Arrays.copyOf(rowid, rowid.length));
								} catch (NullPointerException NPEe) {
									// LOG.error("NPE for VALUE [" + new
									// String(values.get(0).getValue()) +
									// "] ROW ["
									// + Bytes.toString(rowid) + "] in column ["
									// + Bytes.toString(columnFamily) + ":"
									// + Bytes.toString(qualifier) + "]", NPEe);
									throw NPEe;
								} catch (ClassNotFoundException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								}
							}
						}
						values.clear();
					} while (more);
					scanner.close();
				}
			} finally {
				region.closeRegionOperation();
			}
		} catch (NotServingRegionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (RegionTooBusyException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedIOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			rwLock.writeLock().unlock();
		}

	}

	public void removeValueFromIdx(byte[] key, byte[] value) {
		rwLock.writeLock().lock();
		try {
			RowIndex rowIndex = rowIndexMap.get(new ByteArrayWrapper(key));
			if (rowIndex != null) {
				rowIndex.remove(value, maxTreeSize);
			}
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			rwLock.writeLock().unlock();
		}
	}

	public Set<byte[]> filterRowsFromCriteria(Criterion<?> criterion) {
		rwLock.readLock().lock();

		Set<byte[]> rowKeys = new TreeSet<byte[]>(Bytes.BYTES_COMPARATOR);
		// Htable based only handles EQUAL relation
		// Because only the indexing structure knows how to handle different
		// criteria
		// I moved all the case handling from Criterion to this class
		// Modified by Cong

		// for (String value : criterion
		// .getMatchingValueSetFromIndex(rowIndexMap.keySet())) {
		// rowKeys.addAll(rowIndexMap.get(value).getPKRefs());
		// }
		ByteArrayWrapper key = new ByteArrayWrapper(
				(byte[]) criterion.getComparisonValue());

		switch (criterion.getComparisonType()) {

		case EQUAL:
			RowIndex rowIndex = rowIndexMap.get(key);
			if (rowIndex == null) {
				return null;
			} else {
				try {
					rowKeys.addAll(rowIndex.getPKRefs());
					return rowKeys;
				} catch (ClassNotFoundException | IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} finally {
					rwLock.readLock().unlock();
				}
			}
		case RANGE:
			Range range = criterion.getRange();

			// Add lower bound and higher bound filter
			FilterList list = new FilterList(FilterList.Operator.MUST_PASS_ALL);
			SingleColumnValueFilter filter1 = new SingleColumnValueFilter(
					columnFamily, qualifier, CompareOp.GREATER_OR_EQUAL,
					range.getLowerBound());
			list.addFilter(filter1);
			SingleColumnValueFilter filter2 = new SingleColumnValueFilter(
					columnFamily, qualifier, CompareOp.LESS_OR_EQUAL,
					range.getHigherBound());
			list.addFilter(filter2);

			// scan the table based on the filter
			Configuration config = HBaseConfiguration.create();
			HTable table;
			try {
				table = new HTable(config, this.tableName);
				Scan s = new Scan();
				// set the filter lists
				s.setFilter(list);
				s.addColumn(columnFamily, qualifier);
				ResultScanner scanner = table.getScanner(s);
				List<KeyValue> values = new ArrayList<KeyValue>();

				for (Result rr = scanner.next(); rr != null; rr = scanner
						.next()) {
					values = rr.list();
					byte[] rowid = values.get(0).getRow();
					rowKeys.add(rowid);
				}
				scanner.close();
				table.close();
				return rowKeys;

			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} finally {
				rwLock.readLock().unlock();
			}

			return null;
		default:
			rwLock.readLock().unlock();
			return null;
		}
	}

	byte[] getColumnFamily() {
		return columnFamily;
	}

	byte[] getQualifier() {
		return qualifier;
	}

	// public String toString() {
	// for (String key : keySet()) {
	// System.out.print("Key: " + key + "  Values: ");
	// try {
	// for (byte[] value : rowIndexMap.get(key).getPKRefs()) {
	// System.out.print(Bytes.toString(value) + ", ");
	// }
	// System.out.println("");
	// } catch (ClassNotFoundException e) {
	// // TODO Auto-generated catch block
	// e.printStackTrace();
	// } catch (IOException e) {
	// // TODO Auto-generated catch block
	// e.printStackTrace();
	// }
	// }
	// return "";
	// }

	@Override
	public void split(AbstractPluggableIndex daughterRegionA,
			AbstractPluggableIndex daughterRegionB, byte[] splitRow) {

		rwLock.writeLock().lock();
		for (ByteArrayWrapper value : rowIndexMap.keySet()) {

			byte[][] sortedPKRefArray = this.get(value);
			int splitPoint = Arrays.binarySearch(sortedPKRefArray, splitRow,
					ByteUtil.BYTES_COMPARATOR);
			for (int i = 0; i < sortedPKRefArray.length; i++) {
				if ((splitPoint >= 0 && i < splitPoint)
						|| (splitPoint < 0 && i < Math.abs(splitPoint + 1))) {
					daughterRegionA.add(value.get(), sortedPKRefArray[i]);
				} else {
					daughterRegionB.add(value.get(), sortedPKRefArray[i]);
				}
			}
		}

		rwLock.writeLock().unlock();

	}
}
