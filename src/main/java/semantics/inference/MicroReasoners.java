package semantics.inference;

import static org.neo4j.graphdb.RelationshipType.withName;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Result;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.UserFunction;
import semantics.result.NodeResult;
import semantics.result.RelAndNodeResult;

public class MicroReasoners {

  private static final String sloInferenceFormatReturnClassNames = "RETURN $virtLabel as l UNION MATCH (:`%1$s` { `%2$s`: $virtLabel})<-[:`%3$s`*]-(sl:`%1$s`) RETURN distinct sl.`%2$s` as l";
  private static final String scoInferenceCypher = "MATCH (cat)<-[:SCO*0..]-(subcat) WHERE id(cat) = $catId RETURN collect(DISTINCT id(subcat)) AS catIds";
  private static final String scoInferenceCypherBottomUp = "MATCH (cat)<-[:SCO*0..]-(subcat) WHERE id(subcat) = $catId RETURN collect(DISTINCT id(cat)) AS catIds";
  private static final String sroInferenceFormatReturnRelNames = "RETURN $virtRel as r UNION MATCH (:`%1$s` { `%2$s`: $virtRel})<-[:`%3$s`*]-(sr:`%1$s`) RETURN DISTINCT sr.`%2$s` as r";
  private static final String DEFAULT_SLO_REL_NAME = "SLO";
  private static final String DEFAULT_SCO_REL_NAME = "SCO";
  private static final String DEFAULT_IN_CAT_REL_NAME = "IN_CAT";
  private static final String DEFAULT_CAT_LABEL_NAME = "Label";
  private static final String DEFAULT_CAT_NAME_PROP_NAME = "name";
  private static final String DEFAULT_REL_LABEL_NAME = "Relationship";
  private static final String DEFAULT_REL_NAME_PROP_NAME = "name";
  private static final String DEFAULT_SRO_REL_NAME = "SRO";
  private static final boolean DEFAULT_SEARCH_TOP_DOWN = false;

  @Context
  public GraphDatabaseService db;
  @Context
  public Log log;

    /* get nodes with a given label explicit or inferred.
    * semantics (cat:Cat { name: 'xyz'})-[:SCO]->(parent:Cat { name: ''}) */

  @Procedure(mode = Mode.READ)
  @Description("semantics.inference.nodesLabelled('label') - returns all nodes with label 'label' or its sublabels.")
  public Stream<NodeResult> nodesLabelled(@Name("label") String virtLabel,
      @Name(value = "params", defaultValue = "{}") Map<String, Object> props) {

    Map<String, Object> params = new HashMap<String, Object>();
    params.put("virtLabel", virtLabel);
    Result results = db.execute(String.format(sloInferenceFormatReturnClassNames,
        (props.containsKey("catLabel") ? (String) props.get("catLabel") : DEFAULT_CAT_LABEL_NAME),
        (props.containsKey("catNameProp") ? (String) props.get("catNameProp")
            : DEFAULT_CAT_NAME_PROP_NAME),
        (props.containsKey("subCatRel") ? (String) props.get("subCatRel") : DEFAULT_SLO_REL_NAME)),
        params);
    StringBuilder sb = new StringBuilder();
    sb.append("cypher runtime=slotted ");
    boolean isFirstSubLabel = true;
    while (results.hasNext()) {
      Map<String, Object> result = results.next();
      String subLabel = (String) result.get("l");
      if (!isFirstSubLabel) {
        sb.append(" UNION ");
      } else {
        isFirstSubLabel = false;
      }
      sb.append(" MATCH (x:`").append(subLabel).append("`) RETURN x as result ");
    }
    if (!sb.toString().equals("cypher runtime=slotted ")) {
      return db.execute(sb.toString()).stream().map(n -> (Node) n.get("result"))
          .map(NodeResult::new);
    } else {
      return null;
    }
  }

  /* in this case the node representing the category exist in the graph and is explicitly linked to the instances of the category
   *  hence the use of a node as param */
  @Procedure(mode = Mode.READ)
  @Description("semantics.inference.nodesInCategory('category') - returns all nodes connected to Node 'catNode' or its subcategories.")
  public Stream<NodeResult> nodesInCategory(@Name("category") Node catNode,
      @Name(value = "params", defaultValue = "{}") Map<String, Object> props) {

    final String inCatRelName = (props.containsKey("inCatRel") ? (String) props.get("inCatRel")
        : DEFAULT_IN_CAT_REL_NAME);
    final String subCatRelName = (props.containsKey("subCatRel") ? (String) props.get("subCatRel")
        : DEFAULT_SCO_REL_NAME);

    List<Long> subcatIds = getSubcatIds(catNode, subCatRelName);
    Map<String, Object> params = new HashMap<>();
    StringBuilder sb = new StringBuilder();
    sb.append("cypher runtime=slotted ");
    boolean isFirstSubLabel = true;
    for (Long catId : subcatIds) {
      if (!isFirstSubLabel) {
        sb.append(" UNION ");
      } else {
        isFirstSubLabel = false;
      }
      sb.append(" MATCH (x)-[:`").append(inCatRelName).append("`]->(cat) WHERE ID(cat)=$cat_")
          .append(catId).append(" RETURN x as result ");
      params.put("cat_" + catId, catId);
    }
    if (!sb.toString().equals("cypher runtime=slotted ")) {
      return db.execute(sb.toString(), params).stream().map(n -> (Node) n.get("result"))
          .map(NodeResult::new);
    } else {
      return null;
    }
  }

  private List<Long> getSubcatIds(Node catNode, String subCatRelName) {
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("catId", catNode.getId());
    return (List<Long>) db.execute((subCatRelName == null ? scoInferenceCypher
        : scoInferenceCypher.replace("SCO", subCatRelName)), params).next().get("catIds");
  }

  private List<Long> getSuperCatIds(long catNodeId, String subCatRelName) {
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("catId", catNodeId);
    return (List<Long>) db.execute((subCatRelName == null ? scoInferenceCypherBottomUp
        : scoInferenceCypherBottomUp.replace("SCO", subCatRelName)), params).next().get("catIds");
  }

  @Procedure(mode = Mode.READ)
  @Description(
      "semantics.inference.getRels(node,'rel','>') - returns all outgoing relationships of type 'virtRel' "
          +
          "or its subtypes along with the target nodes.")
  public Stream<RelAndNodeResult> getRels(@Name("node") Node node, @Name("rel") String virtRel,
      @Name(value = "params", defaultValue = "{}") Map<String, Object> props) {

    String directionString = (props.containsKey("relDir") ? (String) props.get("relDir") : "");
    Direction direction = (directionString.equals(">") ? Direction.OUTGOING
        : (directionString.equals("<") ? Direction.INCOMING : Direction.BOTH));

    Map<String, Object> params = new HashMap<String, Object>();
    params.put("virtRel", virtRel);

    Result results = db.execute(String.format(sroInferenceFormatReturnRelNames,
        (props.containsKey("relLabel") ? (String) props.get("relLabel") : DEFAULT_REL_LABEL_NAME),
        (props.containsKey("relNameProp") ? (String) props.get("relNameProp")
            : DEFAULT_REL_NAME_PROP_NAME),
        (props.containsKey("subRelRel") ? (String) props.get("subRelRel") : DEFAULT_SRO_REL_NAME)),
        params);
    Set<RelationshipType> rts = new HashSet<RelationshipType>();
    while (results.hasNext()) {
      rts.add(withName((String) results.next().get("r")));
    }

    return StreamSupport.stream(
        node.getRelationships(direction, rts.toArray(new RelationshipType[0])).spliterator(), true)
        .map(n -> new RelAndNodeResult(n, n.getOtherNode(node)));

  }


  @UserFunction
  @Description("semantics.inference.hasLabel(node,label,{}) - checks whether node is explicitly or implicitly labeled as 'label'.")
  public boolean hasLabel(
      @Name("node") Node individual,
      @Name("label") String label,
      @Name(value = "params", defaultValue = "{}") Map<String, Object> props) {
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("virtLabel", label);
    Result results = db.execute(String.format(sloInferenceFormatReturnClassNames,
        (props.containsKey("catLabel") ? (String) props.get("catLabel") : DEFAULT_CAT_LABEL_NAME),
        (props.containsKey("catNameProp") ? (String) props.get("catNameProp")
            : DEFAULT_CAT_NAME_PROP_NAME),
        (props.containsKey("subCatRel") ? (String) props.get("subCatRel") : DEFAULT_SLO_REL_NAME)),
        params);

    Set<String> sublabels = new HashSet<>();
    sublabels.add(label);
    while (results.hasNext()) {
      sublabels.add((String) results.next().get("l"));
    }
    Iterable<Label> labels = individual.getLabels();
    boolean is = false;
    for (Label l : labels) {
      is |= sublabels.contains(l.name());
    }

    return is;
  }


  @UserFunction
  @Description("semantics.inference.inCategory(node, category, {}) - checks whether node is explicitly or implicitly in a category.")
  public boolean inCategory(
      @Name("node") Node individual, @Name("category") Node category,
      @Name(value = "params", defaultValue = "{}") Map<String, Object> props) {

    final String inCatRelName = (props.containsKey("inCatRel") ? (String) props.get("inCatRel")
        : DEFAULT_IN_CAT_REL_NAME);
    final String subCatRelName = (props.containsKey("subCatRel") ? (String) props.get("subCatRel")
        : DEFAULT_SCO_REL_NAME);
    final boolean searchTopDown = (props.containsKey("searchTopDown") ? (boolean) props
        .get("searchTopDown")
        : DEFAULT_SEARCH_TOP_DOWN);

    Iterator<Relationship> relIterator = individual
        .getRelationships(RelationshipType.withName(inCatRelName), Direction.OUTGOING).iterator();

    if (searchTopDown) {
      List<Long> catIds = getSubcatIds(category, subCatRelName);
      boolean is = false;
      while (!is && relIterator.hasNext()) {
        is |= catIds.contains(relIterator.next().getEndNode().getId());
      }
      return is;

    } else {
      boolean is = false;
      while (!is && relIterator.hasNext()) {
        List<Long> catIds = getSuperCatIds(relIterator.next().getEndNode().getId(), subCatRelName);
        is |= catIds.contains(category.getId());
      }
      return is;

    }


  }

}
