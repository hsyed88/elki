package de.lmu.ifi.dbs.elki.algorithm;

/*
 This file is part of ELKI:
 Environment for Developing KDD-Applications Supported by Index-Structures

 Copyright (C) 2013
 Ludwig-Maximilians-Universität München
 Lehr- und Forschungseinheit für Datenbanksysteme
 ELKI Development Team

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import de.lmu.ifi.dbs.elki.data.NumberVector;
import de.lmu.ifi.dbs.elki.data.type.TypeInformation;
import de.lmu.ifi.dbs.elki.data.type.TypeUtil;
import de.lmu.ifi.dbs.elki.database.Database;
import de.lmu.ifi.dbs.elki.database.datastore.DataStore;
import de.lmu.ifi.dbs.elki.database.datastore.DataStoreFactory;
import de.lmu.ifi.dbs.elki.database.datastore.DataStoreUtil;
import de.lmu.ifi.dbs.elki.database.datastore.WritableDataStore;
import de.lmu.ifi.dbs.elki.database.ids.DBID;
import de.lmu.ifi.dbs.elki.database.ids.DBIDUtil;
import de.lmu.ifi.dbs.elki.database.ids.DBIDs;
import de.lmu.ifi.dbs.elki.database.ids.distance.DoubleDistanceKNNHeap;
import de.lmu.ifi.dbs.elki.database.ids.distance.KNNHeap;
import de.lmu.ifi.dbs.elki.database.ids.distance.KNNList;
import de.lmu.ifi.dbs.elki.database.relation.Relation;
import de.lmu.ifi.dbs.elki.distance.DistanceUtil;
import de.lmu.ifi.dbs.elki.distance.distancefunction.DistanceFunction;
import de.lmu.ifi.dbs.elki.distance.distancefunction.SpatialPrimitiveDistanceFunction;
import de.lmu.ifi.dbs.elki.distance.distancefunction.SpatialPrimitiveDoubleDistanceFunction;
import de.lmu.ifi.dbs.elki.distance.distancevalue.Distance;
import de.lmu.ifi.dbs.elki.index.tree.LeafEntry;
import de.lmu.ifi.dbs.elki.index.tree.spatial.SpatialEntry;
import de.lmu.ifi.dbs.elki.index.tree.spatial.SpatialIndexTree;
import de.lmu.ifi.dbs.elki.index.tree.spatial.SpatialNode;
import de.lmu.ifi.dbs.elki.index.tree.spatial.SpatialPointLeafEntry;
import de.lmu.ifi.dbs.elki.logging.Logging;
import de.lmu.ifi.dbs.elki.logging.progress.FiniteProgress;
import de.lmu.ifi.dbs.elki.logging.progress.IndefiniteProgress;
import de.lmu.ifi.dbs.elki.result.ResultUtil;
import de.lmu.ifi.dbs.elki.utilities.datastructures.heap.ComparableMinHeap;
import de.lmu.ifi.dbs.elki.utilities.documentation.Description;
import de.lmu.ifi.dbs.elki.utilities.documentation.Title;
import de.lmu.ifi.dbs.elki.utilities.exceptions.AbortException;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.OptionID;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.constraints.CommonConstraints;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameterization.Parameterization;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameters.IntParameter;

/**
 * Joins in a given spatial database to each object its k-nearest neighbors.
 * This algorithm only supports spatial databases based on a spatial index
 * structure.
 * 
 * Since this method compares the MBR of every single leaf with every other
 * leaf, it is essentially quadratic in the number of leaves, which may not be
 * appropriate for large trees.
 * 
 * @author Elke Achtert
 * @author Erich Schubert
 * 
 * @param <V> the type of FeatureVector handled by this Algorithm
 * @param <D> the type of Distance used by this Algorithm
 * @param <N> the type of node used in the spatial index structure
 * @param <E> the type of entry used in the spatial node
 */
@Title("K-Nearest Neighbor Join")
@Description("Algorithm to find the k-nearest neighbors of each object in a spatial database")
public class KNNJoin<V extends NumberVector<?>, D extends Distance<D>, N extends SpatialNode<N, E>, E extends SpatialEntry> extends AbstractDistanceBasedAlgorithm<V, D, DataStore<KNNList<D>>> {
  /**
   * The logger for this class.
   */
  private static final Logging LOG = Logging.getLogger(KNNJoin.class);

  /**
   * Parameter that specifies the k-nearest neighbors to be assigned, must be an
   * integer greater than 0. Default value: 1.
   */
  public static final OptionID K_ID = new OptionID("knnjoin.k", "Specifies the k-nearest neighbors to be assigned.");

  /**
   * The k parameter.
   */
  int k;

  /**
   * Constructor.
   * 
   * @param distanceFunction Distance function
   * @param k k parameter
   */
  public KNNJoin(DistanceFunction<? super V, D> distanceFunction, int k) {
    super(distanceFunction);
    this.k = k;
  }

  /**
   * Joins in the given spatial database to each object its k-nearest neighbors.
   * 
   * @param database Database to process
   * @param relation Relation to process
   * @return result
   */
  @SuppressWarnings("unchecked")
  public WritableDataStore<KNNList<D>> run(Database database, Relation<V> relation) {
    if(!(getDistanceFunction() instanceof SpatialPrimitiveDistanceFunction)) {
      throw new IllegalStateException("Distance Function must be an instance of " + SpatialPrimitiveDistanceFunction.class.getName());
    }
    Collection<SpatialIndexTree<N, E>> indexes = ResultUtil.filterResults(database, SpatialIndexTree.class);
    if(indexes.size() != 1) {
      throw new AbortException("KNNJoin found " + indexes.size() + " spatial indexes, expected exactly one.");
    }
    // FIXME: Ensure were looking at the right relation!
    SpatialIndexTree<N, E> index = indexes.iterator().next();
    SpatialPrimitiveDistanceFunction<V, D> distFunction = (SpatialPrimitiveDistanceFunction<V, D>) getDistanceFunction();
    DBIDs ids = relation.getDBIDs();

    // data pages
    List<E> ps_candidates = new ArrayList<>(index.getLeaves());
    // knn heaps
    List<List<KNNHeap<D>>> heaps = new ArrayList<>(ps_candidates.size());
    ComparableMinHeap<Task> pq = new ComparableMinHeap<>(ps_candidates.size() * ps_candidates.size() / 10);

    // Initialize with the page self-pairing
    for(int i = 0; i < ps_candidates.size(); i++) {
      E pr_entry = ps_candidates.get(i);
      N pr = index.getNode(pr_entry);
      heaps.add(initHeaps(distFunction, pr));
    }

    // Build priority queue
    final int sqsize = ps_candidates.size() * (ps_candidates.size() - 1) >> 1;
    if(LOG.isDebuggingFine()) {
      LOG.debugFine("Number of leaves: " + ps_candidates.size() + " so " + sqsize + " MBR computations.");
    }
    FiniteProgress mprogress = LOG.isVerbose() ? new FiniteProgress("Comparing leaf MBRs", sqsize, LOG) : null;
    for(int i = 0; i < ps_candidates.size(); i++) {
      E pr_entry = ps_candidates.get(i);
      List<KNNHeap<D>> pr_heaps = heaps.get(i);
      D pr_knn_distance = computeStopDistance(pr_heaps);

      for(int j = i + 1; j < ps_candidates.size(); j++) {
        E ps_entry = ps_candidates.get(j);
        List<KNNHeap<D>> ps_heaps = heaps.get(j);
        D ps_knn_distance = computeStopDistance(ps_heaps);
        D minDist = distFunction.minDist(pr_entry, ps_entry);
        // Resolve immediately:
        if(minDist.isNullDistance()) {
          N pr = index.getNode(ps_candidates.get(i));
          N ps = index.getNode(ps_candidates.get(j));
          processDataPagesOptimize(distFunction, pr_heaps, ps_heaps, pr, ps);
        }
        else if(minDist.compareTo(pr_knn_distance) <= 0 || minDist.compareTo(ps_knn_distance) <= 0) {
          pq.add(new Task(minDist, i, j));
        }
        if(mprogress != null) {
          mprogress.incrementProcessed(LOG);
        }
      }
    }
    if(mprogress != null) {
      mprogress.ensureCompleted(LOG);
    }

    // Process the queue
    FiniteProgress qprogress = LOG.isVerbose() ? new FiniteProgress("Processing queue", pq.size(), LOG) : null;
    IndefiniteProgress fprogress = LOG.isVerbose() ? new IndefiniteProgress("Full comparisons", LOG) : null;
    while(!pq.isEmpty()) {
      Task task = pq.poll();
      List<KNNHeap<D>> pr_heaps = heaps.get(task.i);
      List<KNNHeap<D>> ps_heaps = heaps.get(task.j);
      D pr_knn_distance = computeStopDistance(pr_heaps);
      D ps_knn_distance = computeStopDistance(ps_heaps);
      boolean dor = task.mindist.compareTo(pr_knn_distance) <= 0;
      boolean dos = task.mindist.compareTo(ps_knn_distance) <= 0;
      if(dor || dos) {
        N pr = index.getNode(ps_candidates.get(task.i));
        N ps = index.getNode(ps_candidates.get(task.j));
        if(dor && dos) {
          processDataPagesOptimize(distFunction, pr_heaps, ps_heaps, pr, ps);
        }
        else {
          if(dor) {
            processDataPagesOptimize(distFunction, pr_heaps, null, pr, ps);
          }
          else /* dos */{
            processDataPagesOptimize(distFunction, ps_heaps, null, ps, pr);
          }
        }
        if(fprogress != null) {
          fprogress.incrementProcessed(LOG);
        }
      }
      if(qprogress != null) {
        qprogress.incrementProcessed(LOG);
      }
    }
    if(qprogress != null) {
      qprogress.ensureCompleted(LOG);
    }
    if(fprogress != null) {
      fprogress.setCompleted(LOG);
    }

    WritableDataStore<KNNList<D>> knnLists = DataStoreUtil.makeStorage(ids, DataStoreFactory.HINT_STATIC, KNNList.class);
    // FiniteProgress progress = logger.isVerbose() ? new
    // FiniteProgress(this.getClass().getName(), relation.size(), logger) :
    // null;
    FiniteProgress pageprog = LOG.isVerbose() ? new FiniteProgress("Number of processed data pages", ps_candidates.size(), LOG) : null;
    // int processed = 0;
    for(int i = 0; i < ps_candidates.size(); i++) {
      N pr = index.getNode(ps_candidates.get(i));
      List<KNNHeap<D>> pr_heaps = heaps.get(i);

      // Finalize lists
      for(int j = 0; j < pr.getNumEntries(); j++) {
        knnLists.put(((LeafEntry) pr.getEntry(j)).getDBID(), pr_heaps.get(j).toKNNList());
      }
      // Forget heaps and pq
      heaps.set(i, null);
      // processed += pr.getNumEntries();

      // if(progress != null) {
      // progress.setProcessed(processed, logger);
      // }
      if(pageprog != null) {
        pageprog.incrementProcessed(LOG);
      }
    }
    // if(progress != null) {
    // progress.ensureCompleted(logger);
    // }
    if(pageprog != null) {
      pageprog.ensureCompleted(LOG);
    }
    return knnLists;
  }

  /**
   * Initialize the heaps.
   * 
   * @param distFunction Distance function
   * @param pr Node to initialize for
   * @return List of heaps
   */
  private List<KNNHeap<D>> initHeaps(SpatialPrimitiveDistanceFunction<V, D> distFunction, N pr) {
    List<KNNHeap<D>> pr_heaps = new ArrayList<>(pr.getNumEntries());
    // Create for each data object a knn heap
    for(int j = 0; j < pr.getNumEntries(); j++) {
      pr_heaps.add(DBIDUtil.newHeap(distFunction.getDistanceFactory(), k));
    }
    // Self-join first, as this is expected to improve most and cannot be
    // pruned.
    processDataPagesOptimize(distFunction, pr_heaps, null, pr, pr);
    return pr_heaps;
  }

  /**
   * Processes the two data pages pr and ps and determines the k-nearest
   * neighbors of pr in ps.
   * 
   * @param distFunction the distance to use
   * @param pr the first data page
   * @param ps the second data page
   * @param pr_heaps the knn lists for each data object in pr
   * @param ps_heaps the knn lists for each data object in ps (if ps != pr)
   */
  @SuppressWarnings("unchecked")
  private void processDataPagesOptimize(SpatialPrimitiveDistanceFunction<V, D> distFunction, List<? extends KNNHeap<D>> pr_heaps, List<? extends KNNHeap<D>> ps_heaps, N pr, N ps) {
    if(DistanceUtil.isDoubleDistanceFunction(distFunction)) {
      List<?> khp = (List<?>) pr_heaps;
      List<?> khs = (List<?>) ps_heaps;
      processDataPagesDouble((SpatialPrimitiveDoubleDistanceFunction<? super V>) distFunction, pr, ps, (List<DoubleDistanceKNNHeap>) khp, (List<DoubleDistanceKNNHeap>) khs);
    }
    else {
      for(int j = 0; j < ps.getNumEntries(); j++) {
        final SpatialPointLeafEntry s_e = (SpatialPointLeafEntry) ps.getEntry(j);
        DBID s_id = s_e.getDBID();
        for(int i = 0; i < pr.getNumEntries(); i++) {
          final SpatialPointLeafEntry r_e = (SpatialPointLeafEntry) pr.getEntry(i);
          D distance = distFunction.minDist(s_e, r_e);
          pr_heaps.get(i).insert(distance, s_id);
          if(pr != ps && ps_heaps != null) {
            ps_heaps.get(j).insert(distance, r_e.getDBID());
          }
        }
      }
    }
  }

  /**
   * Processes the two data pages pr and ps and determines the k-nearest
   * neighbors of pr in ps.
   * 
   * @param df the distance function to use
   * @param pr the first data page
   * @param ps the second data page
   * @param pr_heaps the knn lists for each data object
   * @param ps_heaps the knn lists for each data object in ps
   */
  private void processDataPagesDouble(SpatialPrimitiveDoubleDistanceFunction<? super V> df, N pr, N ps, List<DoubleDistanceKNNHeap> pr_heaps, List<DoubleDistanceKNNHeap> ps_heaps) {
    // Compare pairwise
    for(int j = 0; j < ps.getNumEntries(); j++) {
      final SpatialPointLeafEntry s_e = (SpatialPointLeafEntry) ps.getEntry(j);
      DBID s_id = s_e.getDBID();
      for(int i = 0; i < pr.getNumEntries(); i++) {
        final SpatialPointLeafEntry r_e = (SpatialPointLeafEntry) pr.getEntry(i);
        double distance = df.doubleMinDist(s_e, r_e);
        pr_heaps.get(i).insert(distance, s_id);
        if(pr != ps && ps_heaps != null) {
          ps_heaps.get(j).insert(distance, r_e.getDBID());
        }
      }
    }
  }

  /**
   * Compute the maximum stop distance.
   * 
   * @param heaps Heaps list
   * @return the k-nearest neighbor distance of pr in ps
   */
  private D computeStopDistance(List<KNNHeap<D>> heaps) {
    // Update pruning distance
    D pr_knn_distance = null;
    for(KNNHeap<D> knnList : heaps) {
      // set kNN distance of r
      if(pr_knn_distance == null) {
        pr_knn_distance = knnList.getKNNDistance();
      }
      else {
        pr_knn_distance = DistanceUtil.max(knnList.getKNNDistance(), pr_knn_distance);
      }
    }
    if(pr_knn_distance == null) {
      return getDistanceFunction().getDistanceFactory().infiniteDistance();
    }
    return pr_knn_distance;
  }

  @Override
  public TypeInformation[] getInputTypeRestriction() {
    return TypeUtil.array(TypeUtil.NUMBER_VECTOR_FIELD);
  }

  @Override
  protected Logging getLogger() {
    return LOG;
  }

  /**
   * Task in the processing queue.
   * 
   * @author Erich Schubert
   * 
   * @apiviz.exclude
   */
  private class Task implements Comparable<Task> {
    /**
     * Minimum distance.
     */
    final D mindist;

    /**
     * First offset.
     */
    final int i;

    /**
     * Second offset.
     */
    final int j;

    /**
     * Constructor.
     * 
     * @param mindist Minimum distance
     * @param i First offset
     * @param j Second offset
     */
    public Task(D mindist, int i, int j) {
      super();
      this.mindist = mindist;
      this.i = i;
      this.j = j;
    }

    @Override
    public int compareTo(Task o) {
      return mindist.compareTo(o.mindist);
    }
  }

  /**
   * Parameterization class.
   * 
   * @author Erich Schubert
   * 
   * @apiviz.exclude
   */
  public static class Parameterizer<V extends NumberVector<?>, D extends Distance<D>, N extends SpatialNode<N, E>, E extends SpatialEntry> extends AbstractPrimitiveDistanceBasedAlgorithm.Parameterizer<V, D> {
    /**
     * K parameter.
     */
    protected int k;

    @Override
    protected void makeOptions(Parameterization config) {
      super.makeOptions(config);
      IntParameter kP = new IntParameter(K_ID, 1);
      kP.addConstraint(CommonConstraints.GREATER_EQUAL_ONE_INT);
      if(config.grab(kP)) {
        k = kP.getValue();
      }
    }

    @Override
    protected KNNJoin<V, D, N, E> makeInstance() {
      return new KNNJoin<>(distanceFunction, k);
    }
  }
}