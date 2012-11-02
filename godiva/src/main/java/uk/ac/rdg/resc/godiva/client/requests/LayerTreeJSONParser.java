package uk.ac.rdg.resc.godiva.client.requests;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONValue;

public class LayerTreeJSONParser {

    public static LayerMenuItem getTreeFromJson(String wmsUrl, JSONObject json) {
        String nodeLabel = json.get("label").isString().stringValue();
        JSONValue children = json.get("children");
        LayerMenuItem rootItem = new LayerMenuItem(nodeLabel, "rootId", false, wmsUrl);
        JSONArray childrenArray = children.isArray();
        for (int i = 0; i < childrenArray.size(); i++) {
            addNode(childrenArray.get(i).isObject(), rootItem);
        }
        return rootItem;
    }

    private static void addNode(JSONObject json, LayerMenuItem parentItem) {
        final String label = json.get("label").isString().stringValue();
        JSONValue idJson = json.get("id");
        final String id;
        if (idJson != null && !idJson.toString().equals(""))
            id = idJson.isString().stringValue();
        else
            id = "branchNode";
        JSONValue plottableJson = json.get("plottable");
        final Boolean plottable;
        if (plottableJson != null && (plottableJson.isBoolean() != null))
            plottable = plottableJson.isBoolean().booleanValue();
        else
            plottable = true;
        LayerMenuItem newChild = new LayerMenuItem(label, id, plottable);
        parentItem.addChildItem(newChild);

        // The JSONObject is an array of leaf nodes
        JSONValue children = json.get("children");
        if (children != null) {
            /*
             * We have a branch node
             */
            JSONArray childrenArray = children.isArray();
            for (int i = 0; i < childrenArray.size(); i++) {
                addNode(childrenArray.get(i).isObject(), newChild);
            }
        }
    }
}