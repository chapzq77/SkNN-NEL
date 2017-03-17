package ml.generall.resolver

import ml.generall.ner.elements.{ContextElement, _}
import ml.generall.ner.{ElementMeasures, RecoverConcept}
import ml.generall.resolver.dto.ConceptVariant
import ml.generall.sknn.SkNN
import ml.generall.sknn.model.storage.PlainAverageStorage
import ml.generall.sknn.model.storage.elements.BaseElement
import ml.generall.sknn.model.{Model, SkNNNode, SkNNNodeImpl}

import scala.collection.mutable


object SentenceAnalizer {


  def getConceptsToLearn(objList: List[TrainObject], contextSize: Int): List[(String, String, String)] = {

    var res: List[(String, String, String)] = Nil
    val context = ContextElementConverter.convertContext(objList, contextSize).toList
    context.foreach({
      case (leftContext, elem, rightContext) =>
        if (elem.concepts.size > 1) {
          val leftContextString = leftContext.flatMap(elem => elem.tokens.map(_._1)).mkString(" ")
          val rightContextString = rightContext.flatMap(elem => elem.tokens.map(_._1)).mkString(" ")
          elem.concepts.foreach(concept => res = (leftContextString, concept.concept, rightContextString) :: res)
        }
    })
    res
  }

  def wikiToDbpedia(wikilink: String): String = {
    wikilink.replaceAllLiterally("en.wikipedia.org/wiki", "dbpedia.org/resource")
  }

  def toBagOfWordsElement(obj: TrainObject): BaseElement = {
    val element = new BagOfWordElement(obj.tokens.map(lemma => {
      (lemma._1, lemma._2)
    }).toMap, obj.state)
    obj.concepts match {
      case Nil => element
      case List(concept) => {
        val multi = new MultiElement[WeightedSetElement]
        val onto = new OntologyElement(SentenceAnalizer.wikiToDbpedia(concept.concept), conceptWeight = concept.getWeight)
        multi.addElement(onto)
        multi.addElement(element)
        multi.label = obj.state // multi.genLabel
        multi
      }
      case disambiguation: Iterable[ConceptVariant] => {
        val multi = new MultiElement[WeightedSetElement]
        disambiguation
          .view
          .map(x => new OntologyElement(SentenceAnalizer.wikiToDbpedia(x.concept), conceptWeight = x.getWeight))
          .foreach(multi.addElement)
        multi.addElement(element)
        multi.label = obj.state
        multi
      }
    }
  }

}

/**
  * Created by generall on 27.08.16.
  */
class SentenceAnalizer {


  val contextSize = 5
  val searcher = Searcher
  val parser = LocalCoreNLP
  val exampleBuilder = new ExamplesBuilder

  def prepareSentence(sentence: String): List[TrainObject] = {
    val parseRes = parser.process(sentence)

    val groups = parseRes.zipWithIndex
      .groupBy({ case (record, _) => (record.parseTag, 0 /*record.ner*/ , record.groupId) })
      .toList
      .sortBy(x => x._2.head._2)
      .map(pair => (s"${pair._1._1}" /* _${pair._1._2} */ , pair._2.map(_._1))) // creation of state

    Builder.makeTrain(groups)
  }


  /**
    * Filter predicate, keep only OntologyElements
    *
    * @return keep element?
    */
  def filterSequencePredicate(el: ContextElement): Boolean = el.mainElement match {
    case x: MultiElement[_] => x.subElements.exists {
      case y: OntologyElement => y.nonEmpty
      case _ => false
    }
    case y: OntologyElement => y.nonEmpty
    case _ => false
  }


  def filterSequence(seq: List[ContextElement]): List[ContextElement] = seq.filter(filterSequencePredicate)

  /**
    * Prepare training set for disambiguation
    */
  def getTrainingSet(conceptsToLearn: List[(String, String, String)]): List[List[ContextElement]] = conceptsToLearn
    .flatMap(x => exampleBuilder.build(x._2, x._1, x._3))
    .filter(_.nonEmpty)
    .map(convertToContext)


  def convertToContext(objects: List[TrainObject]): List[ContextElement] = filterSequence(
    ContextElementConverter.convert(objects.map(SentenceAnalizer.toBagOfWordsElement), contextSize))


  def getAllWeightedCategories(trainSet: List[List[ContextElement]]): mutable.Map[String, Double] = trainSet.flatMap(_.flatMap(x => x.flatMap {
    case x: OntologyElement => Some(x.features)
    case _ => None
  })).foldLeft(mutable.Map().withDefaultValue(0.0): mutable.Map[String, Double])((acc, x) => OntologyElement.joinFeatures(acc, x))

  /**
    * Updates state of all ContextElements with OntologyElement in main element
    * @param trainSet train set with filtered context elements
    * @param categoryWeights Map of category weights
    */
  def updateStates(trainSet: List[List[ContextElement]], categoryWeights: scala.collection.Map[String, Double]): Unit = {
    trainSet.foreach(seq => {
      seq.foreach(elem => {
        var wikilinksState: Option[String] = None
        val localMap: mutable.Map[String, Double] = mutable.Map().withDefaultValue(0.0)
        elem.foldLeft(localMap) {
          case (acc, x: OntologyElement) =>
            if(x.weight == 1.0) wikilinksState = Some(x.label)
            OntologyElement.joinFeatures(acc, x.features)
          case (acc, _) => acc
        }
        val (state, _) = localMap.maxBy { case (k, v) => v * categoryWeights(k) }
        elem.label = wikilinksState.getOrElse(state)
      })
    })
  }

  def analyse(sentence: String): Unit = {

    /**
      * Prepare target sentence
      */

    val objs = prepareSentence(sentence)


    objs.foreach(_.print())

    val target: List[ContextElement] = convertToContext(objs)

    /**
      * All concepts with disambiguation
      */
    val conceptsToLearn: List[(String, String, String)] = SentenceAnalizer.getConceptsToLearn(objs, contextSize)


    println("conceptsToLearn: ")
    conceptsToLearn.foreach(println)


    /**
      * Prepare training set from disambiguation
      */

    val trainingSet = getTrainingSet(conceptsToLearn)

    /**
      * Update of the states
      */
    val categories = getAllWeightedCategories(trainingSet)
    updateStates(trainingSet, categories)


    // TODO: resolve concepts derirects
    // TODO: Filter valuable concepts


    val model = new Model[BaseElement, SkNNNode[BaseElement]]((label) => {
      new SkNNNodeImpl[BaseElement, PlainAverageStorage[BaseElement]](label, 1)(() => {
        new PlainAverageStorage[BaseElement](ElementMeasures.bagOfWordElementDistance)
      })
    })

    println(s"trainingSet.size: ${trainingSet.size}")

    trainingSet.foreach(seq => model.processSequenceImpl(seq)(onto => List((onto.label, onto))))

    val sknn = new SkNN[BaseElement, SkNNNode[BaseElement]](model)


    val res = sknn.tag(target, 1)((elem, node) => {
      elem match {
        case contextElement: ContextElement => contextElement.mainElement match {
          case bow: BagOfWordElement => bow.label == node.label
          case _ => true
        }
        case _ => true
      }
    })

    val recoveredResult1 = RecoverConcept.recover(target, model.initNode, res.head._1)
    println(s"Weight: ${res.head._2}")
    objs.zip(recoveredResult1).foreach({ case (obj, node) => println(s"${obj.tokens.mkString(" ")} => ${node.label}") })

    /*
    val recoveredResult2 = RecoverConcept.recover(target, model.initNode, res(1)._1)
    println(s"Weight: ${res(1)._2}")
    objs.zip(recoveredResult2).foreach({ case (obj, node) => println(s"${obj.tokens.mkString(" ")} => ${node.label}")})
    */
  }

}
