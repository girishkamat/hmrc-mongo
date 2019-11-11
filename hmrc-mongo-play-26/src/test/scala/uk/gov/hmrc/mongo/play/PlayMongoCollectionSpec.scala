/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.mongo.play

import org.scalatest.{AppendedClues, Matchers, OptionValues, WordSpecLike}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.Matchers.{equal => equal2, _}
import org.bson.codecs.configuration.{CodecRegistries, CodecRegistry}
import com.mongodb.client.result.UpdateResult
import org.mongodb.scala.{Completed, MongoCollection, MongoDatabase}
import org.mongodb.scala.bson.codecs.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala.model.{Filters, Updates}
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.component.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, MongoFormats}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag

class PlayMongoCollectionSpec extends WordSpecLike with ScalaFutures {
  import PlayMongoCollectionSpec._

  val mongoComponent = {
    val databaseName: String = "test-" + this.getClass.getSimpleName
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")
  }


  val playMongoCollection = new PlayMongoCollection[MyObject](
    mongoComponent = mongoComponent,
    collectionName = "myobject",
    domainFormat   = myObjectFormat,
    optRegistry    = Some(CodecRegistries.fromCodecs(
                       Codecs.playFormatCodec(stringWrapperFormat),
                       Codecs.playFormatCodec(booleanWrapperFormat),
                       Codecs.playFormatCodec(longWrapperFormat),
                       Codecs.playFormatCodec(astFormat)
                     )),
    indexes        = Seq.empty
  )

  "PlayMongoCollection.collection" should {

    "read and write object with fields" in {
      mongoComponent.database
        .drop()
        .toFuture
        .futureValue

      // TODO generate vals
      val myObj = MyObject(
        string  = StringWrapper("strVal"),
        boolean = BooleanWrapper(true),
        long    = LongWrapper(System.currentTimeMillis()),
        ast     = Ast.Ast1
      )
      val result = playMongoCollection.collection.insertOne(myObj).toFuture
      result.futureValue shouldBe Completed()

      val writtenObj = playMongoCollection.collection.find().toFuture
      writtenObj.futureValue shouldBe List(myObj)
    }

    "filter by fields" in {
      mongoComponent.database
        .drop()
        .toFuture
        .futureValue

      // TODO generate vals
      val myObj = MyObject(
        string  = StringWrapper("strVal"),
        boolean = BooleanWrapper(true),
        long    = LongWrapper(System.currentTimeMillis()),
        ast     = Ast.Ast1
      )
      val result = playMongoCollection.collection.insertOne(myObj).toFuture
      result.futureValue shouldBe Completed()

      val byString = playMongoCollection.collection.find(filter = Filters.equal("string", myObj.string)).toFuture
      byString.futureValue shouldBe List(myObj)

      val byBoolean = playMongoCollection.collection.find(filter = Filters.equal("boolean", myObj.boolean)).toFuture
      byBoolean.futureValue shouldBe List(myObj)

      val byLong = playMongoCollection.collection.find(filter = Filters.equal("long", myObj.long)).toFuture
      byLong.futureValue shouldBe List(myObj)

      val byAst = playMongoCollection.collection.find(filter = Filters.equal("ast", myObj.ast)).toFuture
      byAst.futureValue shouldBe List(myObj)
    }

    "update fields" in {
      mongoComponent.database
        .drop()
        .toFuture
        .futureValue

      // TODO generate vals
      val originalObj = MyObject(
        string  = StringWrapper("strVal"),
        boolean = BooleanWrapper(true),
        long    = LongWrapper(System.currentTimeMillis()),
        ast     = Ast.Ast1
      )
      val targetObj = MyObject(
        string  = StringWrapper("strVal2"),
        boolean = BooleanWrapper(false),
        long    = LongWrapper(System.currentTimeMillis() + 1),
        ast     = Ast.Ast2
      )

      val result = playMongoCollection.collection.insertOne(originalObj).toFuture
      result.futureValue shouldBe Completed()

      val byString = playMongoCollection.collection.updateOne(filter = new com.mongodb.BasicDBObject(), update = Updates.set("string", targetObj.string)).toFuture
      byString.futureValue.wasAcknowledged shouldBe true

      val byBoolean = playMongoCollection.collection.updateOne(filter = new com.mongodb.BasicDBObject(), update = Updates.set("boolean", targetObj.boolean)).toFuture
      byBoolean.futureValue.wasAcknowledged shouldBe true

      val byLong = playMongoCollection.collection.updateOne(filter = new com.mongodb.BasicDBObject(), update = Updates.set("long", targetObj.long)).toFuture
      byLong.futureValue.wasAcknowledged shouldBe true

      val byAst = playMongoCollection.collection.updateOne(filter = new com.mongodb.BasicDBObject(), update = Updates.set("ast", targetObj.ast)).toFuture
      byAst.futureValue.wasAcknowledged shouldBe true

      val writtenObj = playMongoCollection.collection.find().toFuture
      writtenObj.futureValue shouldBe List(targetObj)
    }
  }
}

object PlayMongoCollectionSpec {

  // TODO avoid this
  implicit val lr = MongoFormats.longReads

  case class StringWrapper(unwrap: String) extends AnyVal

  case class BooleanWrapper(unwrap: Boolean) extends AnyVal

  case class LongWrapper(unwrap: Long) extends AnyVal

  sealed trait Ast
  object Ast {
    case object Ast1 extends Ast
    case object Ast2 extends Ast
  }

  case class MyObject(
    string : StringWrapper
  , boolean: BooleanWrapper
  , long   : LongWrapper
  , ast    : Ast
  )

  implicit lazy val stringWrapperFormat: Format[StringWrapper] =
    implicitly[Format[String]].inmap(StringWrapper.apply, unlift(StringWrapper.unapply))

  implicit lazy val booleanWrapperFormat: Format[BooleanWrapper] =
    implicitly[Format[Boolean]].inmap(BooleanWrapper.apply, unlift(BooleanWrapper.unapply))

  implicit lazy val longWrapperFormat: Format[LongWrapper] =
    implicitly[Format[Long]].inmap(LongWrapper.apply, unlift(LongWrapper.unapply))

  // TODO this is ineffective - codec is looked up by val.getClass
  // i.e. classOf[Ast.Ast1] not classOf[Ast]
  // codec macro would generate a codec for both Ast.Ast1 and Ast.Ast2
  implicit lazy val astFormat: Format[Ast] = new Format[Ast] {
    override def reads(js: JsValue) =
      js.validate[String]
        .flatMap { case "Ast1" => JsSuccess(Ast.Ast1)
                   case "Ast2" => JsSuccess(Ast.Ast2)
                   case other  => JsError(__, s"Unexpected Ast value $other")
                 }

    override def writes(ast: Ast) =
      ast match {
        case Ast.Ast1 => JsString("Ast1")
        case Ast.Ast2 => JsString("Ast2")
      }
  }

  val myObjectFormat =
    ( (__ \ "string" ).format[StringWrapper]
    ~ (__ \ "boolean").format[BooleanWrapper]
    ~ (__ \ "long"   ).format[LongWrapper]
    ~ (__ \ "ast"    ).format[Ast]
    )(MyObject.apply _, unlift(MyObject.unapply))
}