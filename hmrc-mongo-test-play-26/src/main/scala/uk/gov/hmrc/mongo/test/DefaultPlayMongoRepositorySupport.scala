/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.mongo.test

import org.scalatest.TestSuite

import scala.concurrent.duration.DurationInt

/** Provides all the typical mongo testing support.
  *
  * See [[PlayMongoRepositorySupport]] for setting up.
  *
  * In addition it will ensure the database is cleaned, and setup (with indexes and schemas)
  * before each test.
  */
trait DefaultPlayMongoRepositorySupport[A]
    extends PlayMongoRepositorySupport[A]
    with CleanMongoCollectionSupport
    with IndexedMongoQueriesSupport {
  this: TestSuite =>

  override implicit val patienceConfig = PatienceConfig(timeout = 30.seconds, interval = 100.millis)
}