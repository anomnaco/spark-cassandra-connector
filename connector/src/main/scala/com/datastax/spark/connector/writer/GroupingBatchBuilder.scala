package com.datastax.spark.connector.writer

import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.spark.connector.BatchSize
import com.datastax.spark.connector.util.PriorityHashMap

import scala.annotation.tailrec
import scala.collection.Iterator

/**
 * A grouping batch builder is an iterator which take an iterator of single data items and tries to group
 * those items into batches. For each data item, a batch key is computed with the provided function.
 * The items for which the batch key is the same, are grouped together into a batch.
 *
 * When the batch key for the consecutive data items is different, the items are added to separate
 * batches, and those batches are added to the queue. The queue length is limited, therefore when it is
 * full, the longest batch is removed and returned by the iterator.
 * A batch is removed from the queue also in the case when it reaches the batch size limit.
 *
 * The implementation is based on `PriorityHashMap`.
 *
 * @param batchStatementBuilder a configured batch statement builder
 * @param batchKeyGenerator     a key generator for batches - statements with the same key generated by
 *                              this function are grouped together into batches
 * @param batchSize             maximum batch size
 * @param maxBatches            maximum number of batches which can remain in the buffer
 * @param data                  data iterator
 * @tparam T                    data type
 */
private[connector] class GroupingBatchBuilder[T](
    boundStatementBuilder: BoundStatementBuilder[T],
    batchStatementBuilder: BatchStatementBuilder,
    batchKeyGenerator: RichBoundStatementWrapper => Any,
    batchSize: BatchSize,
    maxBatches: Int,
    data: Iterator[T]) extends Iterator[RichStatement] {

  require(maxBatches > 0, "The maximum number of batches must be greater than 0")

  private[this] val batchMap = new PriorityHashMap[Any, Batch](maxBatches)

  /** The method processes the given statement - it adds it to the existing batch or to the new one.
    * If adding the statement would not fit into an existing batch or the new batch would not fit into
    * the buffer, the batch statement is created from the batch and it is returned and the given
    * bound statement is added to a fresh batch. */
  private def processStatement(batchKey: Any, boundStatement: RichBoundStatementWrapper): Option[RichStatement] = {
    batchMap.get(batchKey) match {
      case Some(batch) =>
        updateBatchInMap(batchKey, batch, boundStatement)
      case None =>
        addBatchToMap(batchKey, boundStatement)
    }
  }

  /** Adds the given statement to the batch if possible; If there is no enough capacity in the batch,
    * a batch statement is created and returned; the batch is cleaned and the given statement is added
    * to it. */
  private def updateBatchInMap(batchKey: Any, batch: Batch, newStatement: RichBoundStatementWrapper): Option[RichStatement] = {
    if (batch.add(newStatement, force = false)) {
      batchMap.put(batchKey, batch)
      None
    } else {
      Some(replaceBatch(batch, newStatement, batchKey))
    }
  }

  /** Adds a new batch to the buffer and adds the given statement to it. Returns a statement which had
    * to be dequeued. */
  private def addBatchToMap(batchKey: Any, newStatement: RichBoundStatementWrapper): Option[RichStatement] = {
    if (batchMap.size == maxBatches) {
      Some(replaceBatch(batchMap.dequeue(), newStatement, batchKey))

    } else {
      val batch = Batch(batchSize)
      batch.add(newStatement, force = true)
      batchMap.put(batchKey, batch)
      None
    }
  }

  /** Creates a statement from the given batch and cleans the batch so that it can be reused. */
  @inline
  final private def createStmtAndReleaseBatch(batch: Batch): RichStatement = {
    val stmt = batchStatementBuilder.maybeCreateBatch(batch.statements)
    batch.clear()
    stmt
  }

  /** Creates a statement from the given batch; cleans the batch and adds a given statement to it;
    * updates the entry in the buffer. */
  @inline
  private def replaceBatch(batch: Batch, newStatement: RichBoundStatementWrapper, newBatchKey: Any): RichStatement = {
    val stmt = createStmtAndReleaseBatch(batch)
    batch.add(newStatement, force = true)
    batchMap.put(newBatchKey, batch)
    stmt
  }

  final override def hasNext: Boolean =
    data.hasNext || batchMap.nonEmpty

  @tailrec
  final override def next(): RichStatement = {
    if (data.hasNext) {
      val wrapper = boundStatementBuilder.bind(data.next())
      val key = batchKeyGenerator(wrapper)

      processStatement(key, wrapper) match {
        case Some(batchStmt) => batchStmt
        case _ => next()
      }

    } else if (batchMap.nonEmpty) {
      createStmtAndReleaseBatch(batchMap.dequeue())
    } else {
      throw new NoSuchElementException("Called next() on empty iterator")
    }
  }

}
