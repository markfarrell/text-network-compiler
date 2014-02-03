package edu.berkeley.nlp.syntax

import java.util.Calendar

import scala.language.implicitConversions
import scala.language.postfixOps

import scala.collection.JavaConverters._
import scala.collection.Iterator
import scala.collection.immutable.List
import scala.collection.immutable.Set
import scala.collection.mutable.Stack

import it.uniroma1.dis.wsngroup.gexf4j.core.EdgeType
import it.uniroma1.dis.wsngroup.gexf4j.core.Gexf
import it.uniroma1.dis.wsngroup.gexf4j.core.Graph
import it.uniroma1.dis.wsngroup.gexf4j.core.Mode
import it.uniroma1.dis.wsngroup.gexf4j.core.Node
import it.uniroma1.dis.wsngroup.gexf4j.core.data.Attribute
import it.uniroma1.dis.wsngroup.gexf4j.core.data.AttributeClass
import it.uniroma1.dis.wsngroup.gexf4j.core.data.AttributeList
import it.uniroma1.dis.wsngroup.gexf4j.core.data.AttributeType
import it.uniroma1.dis.wsngroup.gexf4j.core.impl.GexfImpl
import it.uniroma1.dis.wsngroup.gexf4j.core.impl.StaxGraphWriter
import it.uniroma1.dis.wsngroup.gexf4j.core.impl.data.AttributeListImpl
import it.uniroma1.dis.wsngroup.gexf4j.core.viz.NodeShape

package object TreeConversions {

  type LinguisticTree = Tree[String]
  implicit def TreeToTreeEnhancer(tree : LinguisticTree) = new TreeEnhancer(tree)

  /** 
    * Methods added to Tree through implicit conversions with this class.
    **/
  class TreeEnhancer(tree : LinguisticTree) { 

    /** 
      * Find the longest subtree of the tree containing only the labels provided
      * as an argument to this function.
      * TODO: Return longest subtree, not first found. 
      **/
    def longestSubtree(labels : Set[String]) : Option[LinguisticTree] = powerTree() filter { 
      (subtree : LinguisticTree) => { 
        subtree.getChildren().asScala forall { 
          (child : LinguisticTree) => labels contains { child.getLabel() }
        } 
      }
    } sortBy { 
      _.getChildren().size() * -1
    } find { (_) => true } 

    def subsequences(ls : List[LinguisticTree]) : List[LinguisticTree] =  ((0 to ls.size) flatMap { 
        (i : Int) => { 
          val pair : (List[LinguisticTree], List[LinguisticTree]) = ls.splitAt(i)  
          List[List[LinguisticTree]](pair._1, pair._2)
        }
      }).flatten.toList

    def powerTree() : List[LinguisticTree] = {

      if(tree.size() == 1) {
        List[LinguisticTree]()
      } else {  

        val childList : List[LinguisticTree] = tree.getChildren().asScala.toList 
        val childPower : List[List[LinguisticTree]] = subsequences(childList) map { _.powerTree() }
        val flattened : List[LinguisticTree] = childPower flatten
        val prepended : List[LinguisticTree] = childPower map { 
          (s: List[LinguisticTree]) => { 
            val root : LinguisticTree = tree.shallowCloneJustRoot()
            root.setChildren( s.toList.asJava )
            root
          }
        }

        flattened union prepended 

      }

    }

    def terminalList() : List[String] =  tree.iterator().asScala.foldLeft(List[String]()) {
      (ls, child) => if(child.isPreTerminal()) ls ++ List[String](child.getChild(0).getLabel())  else ls
    } 

  }

  /** 
   * Implicitly convert a tree to a list of edges when this object's
   * members are imported. 
   * 
   * Example Usage:
   *    import TreeImplicits._
   *    val edgeList : List[(String, String)] = tree 
   **/
  implicit def TreeToEdgeList(tree : Tree[_]) : List[(String, String)] = { 

    tree.iterator().asScala flatMap { 
      (t1 : Tree[_])  => {

        t1.getChildren().asScala filterNot {
          _.getLabel() == null
        } map { 
          (t2: Tree[_])  => (t1.getLabel().toString(), t2.getLabel().toString())
        }

      }

    } toList

  }

  /** 
   * Implicitly convert a string containing an s-expression, valid according to Penn Treebank guidelines,
   * to a Tree with string labels.
   **/
  implicit def StringToTree(str : String) : Tree[String] = Trees.PennTreeReader.parseEasy(str, false)

  /** 
   * Converts a tree into a civilized GEXF object, capable of being viewed as a graph in Gephi.
   **/
  implicit def TreeToGEXF(tree : Tree[String]) : Gexf = {

    val conditionTags : Set[String] = Set[String]("MD")
    val dependencyTags : Set[String] = Set[String]("IN")
    val clauseTags : Set[String] = Set[String]("S", "SBAR")
    val topicTags : Set[String] = Set[String]("NP", "CC") 
    val actionTags : Set[String] = Set[String]("VB", "VBZ", "VBP", "VBD", "CC")

    abstract class Term
    case class Action(value : String, dependency : Option[Dependency]) extends Term // VB, VBZ, VBP, VBD, CC
    case class Dependency(value : String, clause : Option[Topic]) extends Term
    case class Condition(modal : String, actionResult: Option[Action]) extends Term
    case class Topic(values : List[String], ability : Option[Term]) extends Term //VGD, NP, CC 
    //TODO: Add support for multiple actions: e.g. The dog can walk and might run.
    // Possible expand a sentence before passing it to the environment.

    object Env { 

      /** 
        * Takes a current node assumed to contain a valid subtree produces a topic.
        * Warning: Only a topic can be extracted from sentences of the form 
        * "The dog walks" or the "The dog walks and eats"  because it is like saying 
        * "The dog, who walks and eats". 
        **/
      def toTopic(tree : LinguisticTree) : Option[Topic] = tree.longestSubtree(topicTags) map { 
         (t : LinguisticTree) => Topic(t.terminalList(), toCondition(tree).orElse(toAction(tree)))
      } 

      def toDependency(tree : LinguisticTree) : Option[Dependency] = tree.longestSubtree(dependencyTags) map { 
        ( t : LinguisticTree) => Dependency(t.terminalList().mkString(""), toTopic(tree))
      }

      def toAction(tree : LinguisticTree) : Option[Action] = tree.longestSubtree(actionTags) map {
        ( t : LinguisticTree) => Action(t.terminalList().mkString(""), toDependency(tree)) 
      }

      def toCondition(tree : LinguisticTree) : Option[Condition] = tree.longestSubtree(conditionTags) map {
        ( t : LinguisticTree) => Condition(t.terminalList().mkString(""), toAction(tree)) 
      }


      /** def toTopic(tree : Tree[String]) : Option[Topic]  = {

        val subtree : Option[Tree[String]] = tree.longestSubtree(topicTags)
        subtree match { 
          case Some(tree) => Topic(tree.terminalList(), {

              tree.longestSubtree(Set[String]("VP")) match { 
                case None => List[Term]()
                case Some(verbPhraseTree) => { 
                  val clauseTree : Option[Tree[String]] = verbPhraseTree.longestSubtree(clauseTags)

                  } 

                }

              }
            }) 
          case None => None
        } 

      } **/

    } 

    // TODO: Build list of graph nodes, not list of string; set properties based on state.
    // Assume there are conjunctions e.g. the dog and the cat, when transforming the tree.
    val transform : (Tree[String]) => List[String] = (tree) => {

      tree.iterator().asScala.foldLeft((List[String](), 0)) { 
        (state, node) => {

          val list : List[String] = state._1
          val current : Int = state._2

          node.getLabel() match { 
            case "S" => (list, current)
            case "SBAR" => (list, current) 
            case "IN" => (if(node.isPreTerminal()) node.getChild(0).getLabel() :: list else list, current)
            case "NP" => ({

                val result : String = node.iterator().asScala.foldLeft("") {
                  (str, child) => if(child.isPreTerminal()) str + " " + child.getChild(0).getLabel()  else str
                } 

                if(result.length > 0) result :: list else list 

              }, current)
            case "VP" => {

                //Iterate over children: find and add modals, VB, VBZ, VBP, VBD, VBN or VBG.
                val result : (String, Int) = node.iterator().asScala.foldLeft(("", current)) { 
                  (concatState, child) => {

                    if(child.isPreTerminal()) {

                      val terminalLabel : String = child.getChild(0).getLabel()

                      child.getLabel() match {
                        case "MD" => (concatState._1  + " " + terminalLabel, 1)
                        case _ => (concatState._1 + " " + terminalLabel, concatState._2)
                    } 
                  } else { 
                    concatState
                  } 

                }

              }
              (result._1 :: list, result._2)
            }
            case _ => (list, current)
          }

        }

      }._1.reverse

    }

        
     val gexf : Gexf = new GexfImpl();

     gexf.getMetadata()
         .setLastModified(Calendar.getInstance().getTime())
         .setCreator("Civilize")
         .setDescription("Useful representation of a linguistic tree")

     gexf.setVisualization(true)

     val graph : Graph = gexf.getGraph()
     graph.setDefaultEdgeType(EdgeType.UNDIRECTED).setMode(Mode.STATIC)

     val attrList : AttributeList = new AttributeListImpl(AttributeClass.NODE)
     graph.getAttributeLists().add(attrList)

     

     gexf
  }

} 
