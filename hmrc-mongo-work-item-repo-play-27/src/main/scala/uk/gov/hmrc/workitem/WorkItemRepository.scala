/*
 * Copyright 2021 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.workitem

import com.typesafe.config.Config
import org.bson.types.ObjectId
import org.bson.conversions.Bson
import java.time.{Duration, Instant}
import org.mongodb.scala.bson.BsonDocument
import play.api.libs.json._
import uk.gov.hmrc.mongo.metrix.MetricSource
import org.mongodb.scala.model._

import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import scala.concurrent.{ExecutionContext, Future}

/** The repository to set and get the work item's for processing.
  * See [[pushNew(T,DateTime)]] for creating work items, and [[pullOutstanding]] for retrieving them.
  */
abstract class WorkItemRepository[T, ID](
  collectionName: String,
  mongoComponent: MongoComponent,
  itemFormat    : Format[WorkItem[T]],
  config        : Config,
  val workItemFields: WorkItemFieldNames,
  replaceIndexes: Boolean = true
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[WorkItem[T]](
  collectionName = collectionName,
  mongoComponent = mongoComponent,
  domainFormat   = itemFormat,
  indexes        = Seq(
                     IndexModel(Indexes.ascending(workItemFields.status, workItemFields.updatedAt), IndexOptions().background(true)),
                     IndexModel(Indexes.ascending(workItemFields.status, workItemFields.availableAt), IndexOptions().background(true)),
                     IndexModel(Indexes.ascending(workItemFields.status), IndexOptions().background(true))
                   ),
  replaceIndexes = replaceIndexes
) with Operations.Cancel[ID]
  with Operations.FindById[ID, T]
  with MetricSource {

  /** Returns the current date time for setting the updatedAt field.
    * abstract to allow for test friendly implementations.
    */
  def now(): Instant


  /** Returns the property key which defines the millis in Long format, for the [[inProgressRetryAfter]]
    * to be looked up in the [[config]].
    */
  def inProgressRetryAfterProperty: String


  def metricPrefix: String = collectionName

  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] =
    Future.traverse(ProcessingStatus.values.toList) { status =>
      count(status).map(value => s"$metricPrefix.${status.name}" -> value.toInt)
    }.map(_.toMap)

  /** Returns the timeout of any WorkItems marked as InProgress.
    * WorkItems marked as InProgress will be hidden from [[pullOutstanding]] until this window expires.
    */
  lazy val inProgressRetryAfter: Duration =
    Duration.ofMillis(config.getLong(inProgressRetryAfterProperty))

  private def newWorkItem(
    receivedAt  : Instant,
    availableAt : Instant,
    initialState: T => ProcessingStatus
  )(item: T) =
    WorkItem(
      id           = new ObjectId(),
      receivedAt   = receivedAt,
      updatedAt    = now,
      availableAt  = availableAt,
      status       = initialState(item),
      failureCount = 0,
      item         = item
    )

  private def toDo(item: T): ProcessingStatus = ProcessingStatus.ToDo

  /** Creates a new [[WorkItem]] with status ToDo and availableAt equal to receivedAt */
  def pushNew(item: T, receivedAt: Instant): Future[WorkItem[T]] =
    pushNew(item, receivedAt, receivedAt, toDo _)

  /** Creates a new [[WorkItem]] with availableAt equal to receivedAt */
  def pushNew(item: T, receivedAt: Instant, initialState: T => ProcessingStatus): Future[WorkItem[T]] =
    pushNew(item, receivedAt, receivedAt, initialState)

  /** Creates a new [[WorkItem]] with status ToDo */
  def pushNew(item: T, receivedAt: Instant, availableAt: Instant): Future[WorkItem[T]] =
    pushNew(item, receivedAt, availableAt, toDo _)

  /** Creates a new [[WorkItem]].
    * @param item the item to store in the WorkItem
    * @param receivedAt when the item was received
    * @param availableAt when to defer processing until
    * @param initialState defines the initial state of the WorkItem for the item
    */
  def pushNew(item: T, receivedAt: Instant, availableAt: Instant, initialState: T => ProcessingStatus): Future[WorkItem[T]] = {
    val workItem = newWorkItem(receivedAt, availableAt, initialState)(item)
    collection.insertOne(workItem).toFuture.map(_ => workItem)
  }

  /** Creates a batch of new [[WorkItem]]s with status ToDo and availableAt equal to receivedAt */
  def pushNew(items: Seq[T], receivedAt: Instant): Future[Seq[WorkItem[T]]] =
    pushNew(items, receivedAt, receivedAt, toDo _)

  /** Creates a batch of new [[WorkItem]]s with availableAt equal to receivedAt */
  def pushNew(items: Seq[T], receivedAt: Instant, initialState: T => ProcessingStatus): Future[Seq[WorkItem[T]]] =
    pushNew(items, receivedAt, receivedAt, initialState)

  /** Creates a batch of new [[WorkItem]]s.
    * @param items the items to store as WorkItems
    * @param receivedAt when the items were received
    * @param availableAt when to defer processing until
    * @param initialState defines the initial state of the WorkItems for the item
    */
  def pushNew(items: Seq[T], receivedAt: Instant, availableAt: Instant, initialState: T => ProcessingStatus): Future[Seq[WorkItem[T]]] = {
    val workItems = items.map(newWorkItem(receivedAt, availableAt, initialState))

    collection.insertMany(workItems).toFuture.map { result =>
      if (result.getInsertedIds.size == workItems.size) workItems
      else throw new RuntimeException(s"Only ${result.getInsertedIds.size} items were saved")
    }
  }

  /** Returns a WorkItem to be processed, if available.
    * The item will be atomically set to [[InProgress]], so it will not be picked up by other calls to pullOutstanding until
    * it's status has been explicitly marked as Failed or ToDo, or it's progress status has timed out (set by [[inProgressRetryAfter]]).
    *
    * A WorkItem will be considered for processing in the following order:
    * 1) Has ToDo status, and the availableAt field is before the availableBefore param.
    * 2) Has Failed status, and it was marked as Failed before the failedBefore param.
    * 3) Has InProgress status, and was marked as InProgress before the inProgressRetryAfter configuration. Basically a timeout to ensure WorkItems that don't advance from InProgress do not get stuck.
    *
    * @param failedBefore it will only consider WorkItems in FailedState if they were marked as Failed before the failedBefore. This can avoid retrying a failure immediately.
    * @param availableBefore it will only consider WorkItems where the availableAt field is before the availableBefore
    */
  def pullOutstanding(failedBefore: Instant, availableBefore: Instant): Future[Option[WorkItem[T]]] = {
    def findNextItemByQuery(query: Bson): Future[Option[WorkItem[T]]] =
      collection
        .findOneAndUpdate(
          filter  = query,
          update  = setStatusOperation(ProcessingStatus.InProgress, None),
          options = FindOneAndUpdateOptions()
                      .returnDocument(ReturnDocument.AFTER)
        ).toFutureOption

    def todoQuery: Bson =
      Filters.and(
        Filters.equal(workItemFields.status, ProcessingStatus.toBson(ProcessingStatus.ToDo)),
        Filters.lt(workItemFields.availableAt, availableBefore)
      )

    def failedQuery: Bson =
      Filters.or(
        Filters.and(
          Filters.equal(workItemFields.status, ProcessingStatus.toBson(ProcessingStatus.Failed)),
          Filters.lt(workItemFields.updatedAt, failedBefore),
          Filters.lt(workItemFields.availableAt, availableBefore)
        ),
        Filters.and(
          Filters.equal(workItemFields.status, ProcessingStatus.toBson(ProcessingStatus.Failed)),
          Filters.lt(workItemFields.updatedAt, failedBefore),
          Filters.exists(workItemFields.availableAt, false)
        )
      )

    def inProgressQuery: Bson =
      Filters.and(
        Filters.equal(workItemFields.status, ProcessingStatus.toBson(ProcessingStatus.InProgress)),
        Filters.lt(workItemFields.updatedAt, now.minus(inProgressRetryAfter))
      )

    findNextItemByQuery(todoQuery).flatMap {
      case None => findNextItemByQuery(failedQuery)
                     .flatMap {
                       case None => findNextItemByQuery(inProgressQuery)
                       case item => Future.successful(item)
                     }
      case item => Future.successful(item)
    }
  }

  /** Sets the ProcessingStatus of a WorkItem.
    * It will also update the updatedAt timestamp.
    */
  def markAs(id: ID, status: ProcessingStatus, availableAt: Option[Instant] = None): Future[Boolean] =
    collection.updateOne(
      filter = Filters.equal(workItemFields.id, id),
      update = setStatusOperation(status, availableAt)
    ).toFuture
     .map(_.getMatchedCount > 0)

  /** Sets the ProcessingStatus of a WorkItem to a ResultStatus.
    * It will also update the updatedAt timestamp.
    * It will return false if the WorkItem is not InProgress.
    */
  def complete(id: ID, newStatus: ResultStatus): Future[Boolean] =
    collection.updateOne(
      filter = Filters.and(
                 Filters.equal(workItemFields.id, id),
                 Filters.equal(workItemFields.status, ProcessingStatus.toBson(ProcessingStatus.InProgress))
               ),
      update = setStatusOperation(newStatus, None)
    ).toFuture
     .map(_.getModifiedCount > 0)

  /** Sets the ProcessingStatus of a WorkItem to Cancelled.
    * @return [[StatusUpdateResult.Updated]] if the WorkItem is cancelled,
    * [[StatusUpdateResult.NotFound]] if it's not found,
    * and [[StatusUpdateResult.NotUpdated]] if it's not in a cancellable ProcessingStatus.
    */
  def cancel(id: ID): Future[StatusUpdateResult] = {
    import uk.gov.hmrc.workitem.StatusUpdateResult._
    collection.findOneAndUpdate(
      filter = Filters.and(
                 Filters.equal(workItemFields.id, id),
                 Filters.in(workItemFields.status,
                   ProcessingStatus.cancellable.toSeq.map(ProcessingStatus.toBson): _*
                )
               ),
      update  = setStatusOperation(ProcessingStatus.Cancelled, None),
    ).toFuture
     .flatMap { res =>
       Option(res) match {
         case Some(item) => Future.successful(Updated(
                              previousStatus = item.status,
                              newStatus      = ProcessingStatus.Cancelled
                            ))
         case None       => findById(id).map {
                              case Some(item) => NotUpdated(item.status)
                              case None       => NotFound
                            }
       }
     }
  }

  def findById(id: ID): Future[Option[WorkItem[T]]] =
    collection.find(Filters.equal("_id", id)).toFuture.map(_.headOption)

  /** Returns the number of WorkItems in the specified ProcessingStatus */
  def count(state: ProcessingStatus): Future[Long] =
    collection.countDocuments(filter = Filters.equal(workItemFields.status, state.name)).toFuture

  private def setStatusOperation(newStatus: ProcessingStatus, availableAt: Option[Instant]): Bson =
    Updates.combine(
      Updates.set(workItemFields.status, ProcessingStatus.toBson(newStatus)),
      Updates.set(workItemFields.updatedAt, now),
      (availableAt.map(when => Updates.set(workItemFields.availableAt, when)).getOrElse(BsonDocument())),
      (if (newStatus == ProcessingStatus.Failed) Updates.inc(workItemFields.failureCount, 1) else BsonDocument())
    )
}
object Operations {
  trait Cancel[ID] {
    def cancel(id: ID): Future[StatusUpdateResult]
  }
  trait FindById[ID, T] {
    def findById(id: ID): Future[Option[WorkItem[T]]]
  }
}
