package org.dianahep.sparkroot

import org.dianahep.sparkroot.ast._
import org.dianahep.root4j.interfaces._
import org.dianahep.root4j._
import org.apache.spark.sql._
import org.apache.spark.sql.types._

package object ast
{

  class LeafInfo(val name: String, val className: String, val nElements: Int)
  {
    override def toString = (name, className, nElements).toString
  }
  class LeafElementInfo(override val name: String, override val className: String,
    override val nElements: Int, val myTypeCode: Int) 
    extends LeafInfo(name, className,nElements)
  {
    override def toString = (name, className, nElements, myTypeCode).toString
  }
  class NodeInfo(val name: String, val title: String, val className: String,
    val myType: SRType)
  {
    override def toString = (name, title, className, myType).toString
  }
  class NodeElementInfo(override val name: String, override val title: String, 
    override val className: String, 
    override val myType: SRType, val parentName: String,
    val streamerTypeCode: Int, val myTypeCode: Int, val objClassName: String) 
    extends NodeInfo(name, title, className, myType)
  {
    override def toString = (name, title, className, myType, parentName, 
      streamerTypeCode,  myTypeCode, objClassName).toString
  }

  abstract class AbstractSchemaTree;
  case class RootNode(name: String, nodes: Seq[AbstractSchemaTree]) 
    extends AbstractSchemaTree;
  case class EmptyRootNode(val name: String, var entries: Long) extends AbstractSchemaTree;

  //  simple TBranch/TLeaf representations
  class Leaf(val info: LeafInfo) extends AbstractSchemaTree;
  case class TerminalNode(leaf: Leaf, info: NodeInfo, 
    iter: BasicBranchIterator) extends AbstractSchemaTree;
  case class TerminalMultiLeafNode(leaves: Seq[Leaf], info: NodeInfo,
    iter: StructBranchIterator) extends AbstractSchemaTree;
  case class Node(subnodes: Seq[AbstractSchemaTree], info: NodeInfo) 
    extends AbstractSchemaTree;

  //  TBranchElement/TLeafElement representations
  class LeafElement(override val info: LeafElementInfo) extends Leaf(info);
  case class TerminalNodeElement(leaf: LeafElement, info: NodeElementInfo,
    iter: BranchIterator[Any]) extends AbstractSchemaTree;
  case class NodeElement(subnodes: Seq[AbstractSchemaTree], info: NodeElementInfo)
    extends AbstractSchemaTree;

  /**
   * for simple branches - these are the ROOT Type/Codes => our internal type system
   * @return - return the DataType representing the code
   */
  def assignLeafTypeByLeafClass(leaf: TLeaf): SRType = 
    leaf.getRootClass.getClassName.last match
  {
    case 'C' => SRStringType
    case 'B' => SRByteType
    case 'b' => SRByteType
    case 'S' => SRShortType
    case 's' => SRShortType
    case 'I' => SRIntegerType
    case 'i' => SRIntegerType
    case 'F' => SRFloatType
    case 'D' => SRDoubleType
    case 'L' => SRLongType
    case 'l' => SRLongType
    case 'O' => SRBooleanType
    case _ => SRNull
  }

  /**
   * @return - Return the full Simple SR Data Type for this terminal branch
   */
  def assignBranchType(branch: TBranch): SRType = 
  {
    val leaves = branch.getLeaves
    if (leaves.size > 1) SRStructType(
      for (i <- 0 until leaves.size; leaf=leaves.get(i).asInstanceOf[TLeaf])
        yield (leaf.getName, assignLeafType(leaf))
    )
    else assignLeafType(leaves.get(0).asInstanceOf[TLeaf])
  }

  /*
  def assignBranchElementType(branch: TBranchElement, 
    streamerInfo: TStreamerInfo): SRType = 
  {

  }*/
  
  def assignLeafType(leaf: TLeaf): SRType = 
  {
    if (leaf.getArrayDim>0) // array
      SRArrayType(assignLeafTypeByLeafClass(leaf), leaf.getArrayDim)
    else
      assignLeafTypeByLeafClass(leaf)
  }

  /**
   * @return - returns the AbstractSchemaTree RootNode
   */
  def buildAST(tree: TTree, 
    streamers: Seq[TStreamerInfo], // list of streamers
    requiredColumns: Array[String] // list of column names that must be preserved
    ): AbstractSchemaTree = 
  {
    def synthesizeBranch(branch: TBranch): AbstractSchemaTree = 
    {
      val subs = branch.getBranches
      if (subs.size>0)
      {
        //  
        // complex node
        // TODO: Do we have these cases or TBranch has only Leaves???
        //
        null
      }
      else
      {
        //
        //  simple node - assume 1 leaf only for now
        //  1. extract the information you need(name ,title, classname, datatype)
        //  2. Assign the iterator
        //
        val mytype = assignBranchType(branch)
        val leaves = branch.getLeaves
        if (leaves.size==1)
        {
          val leaf = leaves.get(0).asInstanceOf[TLeaf]
          new TerminalNode(new Leaf(new LeafInfo(
            leaf.getName, leaf.getRootClass.getClassName, leaf.getLen
            )), new NodeInfo(
              branch.getName, branch.getTitle, branch.getRootClass.getClassName, mytype
            ), mytype.getIterator(branch).asInstanceOf[BasicBranchIterator])
        }
        else
          new TerminalMultiLeafNode(
            for (i <- 0 until leaves.size; l=leaves.get(i).asInstanceOf[TLeaf]) yield
              new Leaf(new LeafInfo( l.getName, l.getRootClass.getClassName, l.getLen
              )), new NodeInfo(
                branch.getName, branch.getTitle, branch.getRootClass.getClassName,
                mytype
              ), mytype.getIterator(branch).asInstanceOf[StructBranchIterator]
          )
      }
    }

    def synthesizeBranchElement(branchElement: TBranchElement): AbstractSchemaTree = 
    {
      val subs = branchElement.getBranches
      if (subs.size>0)
      {
        //  complex node element
        val mytype = SRNull;
        new NodeElement(
          for (i <- 0 until subs.size; sub=subs.get(i).asInstanceOf[TBranchElement])
            yield synthesizeBranchElement(sub),
          new NodeElementInfo(branchElement.getName, branchElement.getTitle,
            branchElement.getRootClass.getClassName, mytype,
            branchElement.getParentName,
            branchElement.getStreamerType, branchElement.getType,
            branchElement.getClassName
          )
        )
      }
      else
      {
        // ssimple node element - assume there is only 1 leaf element for now
        val leaf = branchElement.getLeaves.get(0).asInstanceOf[TLeafElement]
        val mytype = SRNull;
        new TerminalNodeElement(
          new LeafElement(new LeafElementInfo(leaf.getName,
            leaf.getRootClass.getClassName, leaf.getLen,
            leaf.getType
          )), 
          new NodeElementInfo(branchElement.getName, branchElement.getTitle,
            branchElement.getRootClass.getClassName, mytype,
            branchElement.getParentName,
            branchElement.getStreamerType, branchElement.getType,
            branchElement.getClassName
          ), null
        )
      }
    }

    def synthesize(branch: TBranch): AbstractSchemaTree = 
    {
      if (branch.isInstanceOf[TBranchElement])
        synthesizeBranchElement(branch.asInstanceOf[TBranchElement])
      else
        synthesizeBranch(branch)
    }

    requiredColumns match {
      // for the initialization stage - all the columns to be mapped
      case null => new RootNode(tree.getName,
        for (i <- 0 until tree.getNBranches; b=tree.getBranch(i))
          yield synthesize(b)
      )
      // for the cases like count.... 
      case Array() => new EmptyRootNode(tree.getName, tree.getEntries)
      // for the non-empty list of columns that are required by for a query
      case _ => new RootNode(tree.getName,
        for (i <- 0 until tree.getNBranches; b=tree.getBranch(i) 
          if requiredColumns.contains(b.getName()))
          yield synthesize(b)
      )
    }
  }

  /**
   * @return Spark DataFrame Schema
   */
  def buildSparkSchema(ast: AbstractSchemaTree): StructType =
  {
    def iterate(node: AbstractSchemaTree): StructField = node match {
      case Node(subnodes, info) => StructField(info.name, StructType(
        for (x <- subnodes) yield iterate(x)
      ))
      case TerminalNode(leaf, info, iter) => StructField(info.name,
        info.myType.toSparkType)
      case TerminalMultiLeafNode(leaves, info, iter) => StructField(info.name,
        info.myType.toSparkType
      )
      case NodeElement(subnodes, info) => StructField(info.name, StructType(
        for (x <- subnodes) yield iterate(x)
      ))
      case TerminalNodeElement(leaf, info, iter) => null
      case _ => null
    }
    
    ast match {
      case RootNode(_, nodes) => StructType(
        for (x <- nodes) yield iterate(x)
      )
      case EmptyRootNode(_, _) => StructType(Seq())
      case _ => null
    }
  }

  /**
   * @return Spark DataFrame 1 Row
   */
  def buildSparkRow(ast: AbstractSchemaTree): Row = 
  {
    def iterate(node: AbstractSchemaTree): Any = node match {
      case Node(subnodes, info) => Row.fromSeq(
        for (x <- subnodes) yield iterate(x)
      )
      case TerminalNode(leaf, info, iter) => iter.next
      case TerminalMultiLeafNode(_, info, iter) => Row.fromSeq(iter.next)
      case NodeElement(subnodes, info) => Row.fromSeq(
        for (x <- subnodes) yield iterate(x)
      )
      case TerminalNodeElement(leaf, info, iter) => iter.next
      case _ => null
    }
    
    ast match {
      case RootNode(_, nodes) => Row.fromSeq(
        for (x <- nodes) yield iterate(x)
      )
      case EmptyRootNode(_, _) => {ast.asInstanceOf[EmptyRootNode].entries-=1; Row();}
    }
  }

  /**
   * @return - void
   * prints the Tree
   */
  def printAST(ast: AbstractSchemaTree): Unit = 
  {
    def __print__(node: AbstractSchemaTree, level: Int): Unit = node match 
    {
      case RootNode(name, nodes) => {
        println(name)
        for (x <- nodes) __print__(x, level+2)
      }
      case EmptyRootNode(name, entries) => println(name + " Entries=" + entries)
      case Node(subnodes, info) => {
        println(("  "*level) + info)
        for (x <- subnodes) __print__(x, level+2)
      }
      case TerminalNode(leaf, info, iter) => 
        println(("  "*level) + info + " -> " + leaf.info)
      case TerminalMultiLeafNode(leaves, info, iter) => {
        print(("  "*level) + info + " -> " + leaves.map(_.info.toString).mkString(" :: "))
      }
      case NodeElement(subnodes, info) => {
        println(("  "*level) + info)
        for (x <- subnodes) __print__(x, level+2)
      }
      case TerminalNodeElement(leaf, info, iter) => 
        println(("  "*level) + info + " -> " + leaf.info)
      case _ => println(null)
    }

    __print__(ast, 0)
  }

  def containsNext(ast: AbstractSchemaTree): Boolean = ast match {
    case RootNode(name, nodes) => containsNext(nodes.head)
    case EmptyRootNode(name, entries) => entries>0
    case Node(subnodes, info) => containsNext(subnodes head)
    case TerminalNode(leaf, info, iter) => iter.hasNext
    case TerminalMultiLeafNode(_, _, iter) => iter.hasNext
    case NodeElement(subnodes, info) => containsNext(subnodes head)
    case TerminalNodeElement(leaf, info, iter) => false
    case _ => false
  }

  /**
   * some utils
   */
  def findTree(dir: TDirectory): TTree =
  {
    for (i <- 0 until dir.nKeys) {
      val obj = dir.getKey(i).getObject.asInstanceOf[core.AbstractRootObject]
      if (obj.getRootClass.getClassName == "TDirectory" ||
        obj.getRootClass.getClassName == "TTree") 
      {
        if (obj.getRootClass.getClassName == "TDirectory")
          return findTree(obj.asInstanceOf[TDirectory])
        else (obj.getRootClass.getClassName == "TTree")
        return obj.asInstanceOf[TTree]
      }
    }
    null
  }
}
