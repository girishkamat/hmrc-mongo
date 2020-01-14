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

package uk.gov.hmrc.mongo

import com.mongodb.ConnectionString
import org.mongodb.scala.{MongoClient, MongoDatabase}

trait MongoComponent {
  def client: MongoClient
  def database: MongoDatabase
}

object MongoComponent {
  def apply(mongoUri: String): MongoComponent =
    new MongoComponent {
      override val client: MongoClient     = MongoClient(mongoUri)
      override val database: MongoDatabase = client.getDatabase(new ConnectionString(mongoUri).getDatabase)
    }
}
