package edu.umn.cs.spatialHadoop.indexing;

import edu.umn.cs.spatialHadoop.OperationsParams;
import edu.umn.cs.spatialHadoop.core.SpatialSite;
import edu.umn.cs.spatialHadoop.io.TextSerializerHelper;
import edu.umn.cs.spatialHadoop.operations.OperationMetadata;
import edu.umn.cs.spatialHadoop.util.FileUtil;
import edu.umn.cs.spatialHadoop.util.MetadataUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.util.GenericOptionsParser;

import java.io.IOException;
import java.util.*;

/**
 * Appends new data to an existing index
 */
@OperationMetadata(shortName="insert",
description = "Insert the data in a given path to an existing index")
public class IndexInsert {
  private static final Log LOG = LogFactory.getLog(IndexInsert.class);

  public static void flush(Path inPath, Path indexPath, OperationsParams params) throws IOException, ClassNotFoundException, InterruptedException {
    flush(new Path[] {inPath}, indexPath, params);
  }

  /**
   * Takes all the data in input paths and inserts all of them into an existing index.
   * If the indexPath points to a non-existing directory, an index is created.
   * @param inPath
   * @param indexPath
   * @param params
   * @throws IOException
   * @throws ClassNotFoundException
   * @throws InterruptedException
   */
  public static void flush(Path[] inPath, Path indexPath, OperationsParams params) throws IOException, ClassNotFoundException, InterruptedException {
    FileSystem fs = indexPath.getFileSystem(params);
    if (!fs.exists(indexPath)) {
      // A new index, create it
      Indexer.index(inPath, indexPath, params);
    } else {
      // An existing index, append to it
      Path tempPath;
      do {
        tempPath = new Path(indexPath.getParent(), Integer.toString((int) (Math.random()*1000000)));
      } while (fs.exists(tempPath));
      try {
        // Index the input in reference to the existing index
        Indexer.repartition(inPath, tempPath, indexPath, params);

        // Concatenate corresponding files
        Map<Integer, Partition> mergedIndex = new HashMap<Integer, Partition>();
        for (Partition p : MetadataUtil.getPartitions(indexPath, params))
          mergedIndex.put(p.cellId, p);
        for (Partition newP : MetadataUtil.getPartitions(tempPath, params)) {
          Partition existingP = mergedIndex.get(newP.cellId);
          if (existingP == null) {
            // Sanity check. This should never happen if things go smoothly
            throw new RuntimeException(String.format("New partition %s did not have a corresponding existing partition",
                newP.toString()));
          }
          // Combine the partition information
          existingP.expand(newP);
          // Combine the file
          Path pathOfExisting = new Path(indexPath, existingP.filename);
          Path pathOfNew = new Path(tempPath, newP.filename);
          FileUtil.concat(params, fs, pathOfExisting, pathOfNew);
        }
        // Write back the merged partitions as a new global index
        Path masterFilePath = fs.listStatus(indexPath, new PathFilter() {
          @Override
          public boolean accept(Path path) {
            return path.getName().startsWith("_master");
          }
        })[0].getPath();
        writeMasterFile(fs, masterFilePath, mergedIndex.values());

        Partitioner.generateMasterWKT(fs, masterFilePath);
      } finally {
        fs.delete(tempPath, true);
      }
    }
  }

  /**
   * Writes a _master file given a collection of partitions.
   * @param fs
   * @param masterFilePath
   * @param partitions
   * @throws IOException
   */
  private static void writeMasterFile(FileSystem fs, Path masterFilePath,
                                      Collection<Partition> partitions) throws IOException {
    FSDataOutputStream out = fs.create(masterFilePath, true);
    Text line = new Text();
    for (Partition p : partitions) {
      line.clear();
      p.toText(line);
      TextSerializerHelper.appendNewLine(line);
      out.write(line.getBytes(), 0, line.getLength());
    }
    out.close();
  }

  /**
   * Runs the reorganize step on an existing index. This function inspects the
   * current state of the index and improves it by identifying a set of partitions
   * that are causing the quality of the index to be low and have a high opportunity
   * of being optimized. These partitions are then deleted and reindexed.
   * @param indexPath A path to an existing index
   * @param params
   * @throws IOException
   * @throws InterruptedException
   */
  public static void reorganize(Path indexPath, List<List<Partition>> splitGroups,
                                OperationsParams params) throws IOException, InterruptedException {
    FileSystem fs = indexPath.getFileSystem(params);
    // A list of temporary paths where the reorganized partitions will be stored.
    Path[] tempPaths = new Path[splitGroups.size()];
    Thread[] indexJobs = new Thread[splitGroups.size()];
    for (int iGroup = 0; iGroup < splitGroups.size(); iGroup++) {
      List<Partition> group = splitGroups.get(iGroup);
      final OperationsParams indexParams = new OperationsParams(params);
      indexParams.setBoolean("local", false);
      final Path[] inPaths = new Path[group.size()];
      for (int iPartition = 0; iPartition < group.size(); iPartition++) {
        inPaths[iPartition] = new Path(indexPath, group.get(iPartition).filename);
      }
      do {
        tempPaths[iGroup] = new Path(indexPath.getParent(), Integer.toString((int) (Math.random() * 1000000)));
      } while (fs.exists(tempPaths[iGroup]));
      final Path tempPath = tempPaths[iGroup];
      indexJobs[iGroup] = new Thread() {
        @Override public void run() {
          try {
            Indexer.index(inPaths, tempPath, indexParams);
          } catch (IOException e) {
            e.printStackTrace();
          } catch (InterruptedException e) {
            e.printStackTrace();
          } catch (ClassNotFoundException e) {
            e.printStackTrace();
          }
        }
      };
      indexJobs[iGroup].setName("Index Group #"+iGroup);
      indexJobs[iGroup].start();

    }

    // A list of all the new partitions (after reorganization)
    List<Partition> mergedPartitions = MetadataUtil.getPartitions(indexPath, params);
    int maxId = Integer.MIN_VALUE;
    for (Partition p : mergedPartitions)
      maxId = Math.max(maxId, p.cellId);
    // Wait until all index jobs are done
    for (int iGroup = 0; iGroup < indexJobs.length; iGroup++) {
      indexJobs[iGroup].join();
      // Remove all partitions that were indexed by the job
      for (Partition oldP : splitGroups.get(iGroup)) {
        if (!mergedPartitions.remove(oldP))
          throw new RuntimeException("The partition " + oldP + " is being reorganized but does not exist in " + indexPath);
      }

      ArrayList<Partition> newPartitions = MetadataUtil.getPartitions(tempPaths[iGroup], params);
      for (Partition newPartition : newPartitions) {
        // Generate a new ID and filename to ensure it does not override an existing file
        newPartition.cellId = ++maxId;
        String newName = String.format("part-%05d", newPartition.cellId);
        // Copy the extension of the existing file if it exists
        int lastDot = newPartition.filename.lastIndexOf('.');
        if (lastDot != -1)
          newName += newPartition.filename.substring(lastDot);
        fs.rename(new Path(tempPaths[iGroup], newPartition.filename), new Path(indexPath, newName));
        newPartition.filename = newName;
        mergedPartitions.add(newPartition);
      }
    }
    // Write back the partitions to the master file
    Path masterPath = fs.listStatus(indexPath, SpatialSite.MasterFileFilter)[0].getPath();
    writeMasterFile(fs, masterPath, mergedPartitions);

    // Delete old partitions that have been reorganized and replaced
    for (List<Partition> group : splitGroups) {
      for (Partition partition : group) {
        fs.delete(new Path(indexPath, partition.filename), false);
      }
    }

    // Delete all temporary paths that are supposed to be empty of data
    for (Path tempPath : tempPaths)
      fs.delete(tempPath, true);
  }

  protected static void printUsage() {
    System.out.println("Adds more data to an existing index");
    System.out.println("Parameters (* marks required parameters):");
    System.out.println("<input file> - (*) Path to input file that contains the new data");
    System.out.println("<index path> - (*) Path to the index");
    System.out.println("shape:<point|rectangle|polygon> - (*) Type of shapes stored in input file");
    System.out.println("sindex:<index> - Type of spatial index (grid|str|str+|rtree|r+tree|quadtree|zcurve|hilbert|kdtree)");
    System.out.println("gindex:<index> - Type of the global index (grid|str|rstree|kdtree|zcurve|hilbert|quadtree)");
    System.out.println("lindex:<index> - Type of the local index (rrstree)");
    GenericOptionsParser.printGenericCommandUsage(System.out);
  }

  /**
   * Adds all the data in a path to an existing index and reorganize the index
   * if needed.
   * @param newDataPath
   * @param indexPath
   * @param params
   * @throws IOException
   * @throws ClassNotFoundException
   * @throws InterruptedException
   */
  public static void addToIndex(Path newDataPath, Path indexPath, OperationsParams params)
      throws IOException, ClassNotFoundException, InterruptedException {
    long t0 = System.nanoTime();

    // Step 1: Flush the new batch to the index
    flush(newDataPath, indexPath, params);
    long t1 = System.nanoTime();
    LOG.info(String.format("Append done in %f seconds\n", (t1-t0)*1E-9));

    // Step 2: Select the partitions that need to be reorganized
    List<List<Partition>> splitGroups = RTreeOptimizer.getSplitGroups(indexPath, params, RTreeOptimizer.OptimizerType.MaximumReducedCost);
    long t2 = System.nanoTime();
    LOG.info(String.format("Partition selection done in %f seconds\n", (t2-t1)*1E-9));

    // Step 3: Reorganize the selected groups of partitions
    reorganize(indexPath, splitGroups, params);
    long t3 = System.nanoTime();
    LOG.info(String.format("Reorganization done in %f seconds\n", (t3-t2)*1E-9));
  }

  public static void main(String[] args) throws IOException, InterruptedException, ClassNotFoundException {
    OperationsParams params = new OperationsParams(new GenericOptionsParser(args));

    Path newDataPath = params.getInputPath();
    Path existingIndexPath = params.getOutputPath();

    // The spatial index to use
    long t1 = System.nanoTime();
    addToIndex(newDataPath, existingIndexPath, params);
    long t2 = System.nanoTime();
    System.out.printf("Total insertion time %f seconds\n",(t2-t1)*1E-9);
  }
}
