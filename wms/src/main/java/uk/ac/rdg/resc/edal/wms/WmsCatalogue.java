/*******************************************************************************
 * Copyright (c) 2013 The University of Reading
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the University of Reading, nor the names of the
 *    authors or contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/

package uk.ac.rdg.resc.edal.wms;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.joda.time.Chronology;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.rdg.resc.edal.dataset.Dataset;
import uk.ac.rdg.resc.edal.dataset.GridDataset;
import uk.ac.rdg.resc.edal.domain.TemporalDomain;
import uk.ac.rdg.resc.edal.exceptions.EdalException;
import uk.ac.rdg.resc.edal.feature.MapFeature;
import uk.ac.rdg.resc.edal.graphics.style.util.FeatureCatalogue;
import uk.ac.rdg.resc.edal.graphics.style.util.PlottingDomainParams;
import uk.ac.rdg.resc.edal.metadata.VariableMetadata;
import uk.ac.rdg.resc.edal.util.CollectionUtils;
import uk.ac.rdg.resc.edal.wms.exceptions.WmsLayerNotFoundException;
import uk.ac.rdg.resc.edal.wms.util.ContactInfo;
import uk.ac.rdg.resc.edal.wms.util.ServerInfo;
import uk.ac.rdg.resc.edal.wms.util.StyleDef;
import uk.ac.rdg.resc.edal.wms.util.WmsUtils;

/**
 * This class encapsulates the elements needed to implement a specific WMS.
 * Subclasses should extend this to implement their own configuration system.
 * 
 * This includes:
 * 
 * <li>Global server settings
 * 
 * <li>Contact information for the server
 * 
 * <li>Retrieval/generation of layer names from dataset/variable IDs
 * 
 * <li>Defining default plotting parameters for WMS layers
 * 
 * @author Guy Griffiths
 */
public abstract class WmsCatalogue implements FeatureCatalogue {
    private static final Logger log = LoggerFactory.getLogger(WmsCatalogue.class);

    private Map<String, StyleDef> styleDefs = new HashMap<String, StyleDef>();

    public WmsCatalogue() {
        /*
         * We want the catalogue to read all possible XML templates for
         * supported styles. This includes 2 parts:
         * 
         * 1. Reading the styles which are packaged with the edal-wms module
         * (i.e. from the JAR file) 2. Reading any additional styles defined by
         * a user (i.e. from the classpath)
         */
        try {
            /*
             * This reads all styles from the JAR file
             */
            Pattern styleXmlPath = Pattern.compile("^styles/(.*)\\.xml$");
            CodeSource src = WmsCatalogue.class.getProtectionDomain().getCodeSource();
            if (src != null) {
                URL jar = src.getLocation();
                ZipInputStream zip = new ZipInputStream(jar.openStream());
                while (true) {
                    ZipEntry e = zip.getNextEntry();
                    if (e == null) {
                        break;
                    }
                    String name = e.getName();
                    Matcher matcher = styleXmlPath.matcher(name);
                    if (matcher.matches()) {
                        StyleDef style = processStyle(matcher.group(1), zip);
                        if (style != null) {
                            styleDefs.put(style.getStyleName(), style);
                        }
                    }
                }
            } else {
                /* Fail... */
            }
        } catch (Exception e) {
            log.error("Problem processing styles in edal-wms module");
        }
        try {
            /*
             * This reads all styles from the WEB-INF/classes/styles directory
             */
            Pattern styleXmlPath = Pattern.compile("^(.*)\\.xml$");
            File stylesDir = new File(getClass().getResource("/styles/").toURI());
            if (stylesDir.isDirectory()) {
                for (File styleFile : stylesDir.listFiles()) {
                    Matcher matcher = styleXmlPath.matcher(styleFile.getName());
                    if (matcher.matches()) {
                        StyleDef style = processStyle(matcher.group(1), new FileInputStream(
                                styleFile));
                        if (style != null) {
                            styleDefs.put(style.getStyleName(), style);
                        }
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            /*
             * We ignore this exception since it just means that the styles
             * directory is missing from the classpath
             */
            if(!e.getMessage().contains("URI is not hierarchical")) {
                throw e;
            }
        } catch (Exception e) {
            log.error("Problem processing styles on classpath", e);
        }
    }

    @Override
    public MapFeatureAndMember getFeatureAndMemberName(String id, PlottingDomainParams params)
            throws EdalException {
        Dataset dataset = getDatasetFromLayerName(id);
        String variable = getVariableFromId(id);
        if (dataset instanceof GridDataset) {
            GridDataset gridDataset = (GridDataset) dataset;
            TemporalDomain temporalDomain = gridDataset.getVariableMetadata(variable)
                    .getTemporalDomain();
            Chronology chronology = null;
            if (temporalDomain != null) {
                chronology = temporalDomain.getChronology();
            }
            MapFeature mapData = gridDataset.readMapData(CollectionUtils.setOf(variable),
                    WmsUtils.getImageGrid(params), params.getTargetZ(),
                    params.getTargetT(chronology));
            /*
             * TODO Caching probably goes here
             */
            return new MapFeatureAndMember(mapData, variable);
        } else {
            throw new UnsupportedOperationException("Currently only gridded data is supported");
        }
        /*
         * TODO process other types of Dataset here (i.e. InSituDataset which
         * doesn't yet exist)
         */
    }

    /**
     * Gets the {@link VariableMetadata} object corresponding to a named layer
     * 
     * @param layerName
     *            The name of the WMS layer
     * @return The corresponding {@link VariableMetadata}
     * @throws WmsLayerNotFoundException
     *             If the WMS layer name doesn't map to a variable
     */
    public VariableMetadata getVariableMetadataFromId(String layerName)
            throws WmsLayerNotFoundException {
        Dataset dataset = getDatasetFromLayerName(layerName);
        String variableFromId = getVariableFromId(layerName);
        if (dataset != null && variableFromId != null) {
            return dataset.getVariableMetadata(variableFromId);
        } else {
            throw new WmsLayerNotFoundException("The layer name " + layerName
                    + " doesn't map to a variable");
        }
    }

    /**
     * Gets a {@link List} of styles which are supported for the supplied
     * variable
     * 
     * @param variableMetadata
     *            The {@link VariableMetadata} representing the variable to
     *            check
     * @return A {@link List} containing all supported WMS styles which can plot
     *         the supplied variable.
     */
    public List<StyleDef> getSupportedStyles(VariableMetadata variableMetadata) {
        List<StyleDef> supportedStyles = new ArrayList<StyleDef>();
        /*
         * Loop through all loaded style definitions
         */
        for (StyleDef styleDef : styleDefs.values()) {
            /*
             * Assume the style is supported
             */
            boolean currentStyleSupported = true;
            /*
             * If this style needs the named layer, but it is not scalar (i.e.
             * has no scalar data field which can be read) then it cannot be
             * supported
             */
            if (styleDef.needsNamedLayer() && !variableMetadata.isScalar()) {
                currentStyleSupported = false;
                continue;
            }

            List<String> requiredChildren = styleDef.getRequiredChildren();
            if (requiredChildren != null && !requiredChildren.isEmpty()) {
                for (String requiredChild : requiredChildren) {
                    VariableMetadata childMetadata = variableMetadata.getChildWithRole(requiredChild);
                    if (childMetadata == null || !childMetadata.isScalar()) {
                        /*
                         * We required a child layer which is either missing or
                         * not scalar
                         */
                        currentStyleSupported = false;
                        continue;
                    }
                }
            }
            if (currentStyleSupported) {
                supportedStyles.add(styleDef);
            }
        }
        return supportedStyles;
    }

    /**
     * Returns the a {@link Map} of keys used in XML templates to the concrete
     * WMS layer names which they represent.
     * 
     * @param layerName
     *            The WMS layer name to plot
     * @param styleName
     *            The style name to be plotted
     * @return A {@link Map} of keys used in XML templates to the concrete WMS
     *         layer names which they represent.
     * @throws WmsLayerNotFoundException
     *             If the layer specified does not exist on this WMS server
     */
    public Map<String, String> getStyleTemplateLayerNames(String layerName, String styleName)
            throws WmsLayerNotFoundException {
        StyleDef styleDef = styleDefs.get(styleName);
        Map<String, String> layerKeyToLayerName = new HashMap<String, String>();
        if (styleDef.needsNamedLayer()) {
            layerKeyToLayerName.put("layerName", layerName);
        }
        VariableMetadata layerMetadata = getVariableMetadataFromId(layerName);
        for (String childPurpose : styleDef.getRequiredChildren()) {
            layerKeyToLayerName.put(
                    "layerName-" + childPurpose,
                    getLayerName(layerMetadata.getDataset().getId(), layerMetadata
                            .getChildWithRole(childPurpose).getId()));
        }
        return layerKeyToLayerName;
    }

    /**
     * Processes an XML stream representing a style template.
     * 
     * @param name
     *            The name of the style
     * @param inputStream
     *            The input stream containing the XML
     * @return A {@link StyleDef} representing the properties of the style
     * @throws IOException
     */
    private StyleDef processStyle(String name, InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

        boolean usesPalette = false;
        boolean needsNamedLayer = false;
        Set<String> requiredChildren = new HashSet<String>();

        String line = null;
        /*
         * Checks for the string "$layerName:xxx" and stores the "xxx" part
         */
        Pattern pattern = Pattern.compile(".*\\$layerName-?(\\w*)\\W?+.*");
        while ((line = reader.readLine()) != null) {
            /*
             * Checks for the string "$paletteName"
             */
            if (line.matches(".*\\$paletteName\\W.*")) {
                usesPalette = true;
            }
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                String childSuffix = matcher.group(1);
                if ("".equals(childSuffix)) {
                    /*
                     * We have the string "$layerName", so we need the named
                     * layer
                     */
                    needsNamedLayer = true;
                } else {
                    /*
                     * We have the string "$layerName-xxx", so we need the child
                     * layer "xxx"
                     */
                    requiredChildren.add(childSuffix);
                }
            }
        }

        return new StyleDef(name, requiredChildren, usesPalette, needsNamedLayer);
    }

    /**
     * @return The main server metadata for this server
     */
    public abstract ServerInfo getServerInfo();

    /**
     * @return The main contact information for this server
     */
    public abstract ContactInfo getContactInfo();

    /**
     * @return <code>true</code> if this server allows capabilities documents to
     *         be generated for all datasets
     */
    public abstract boolean allowsGlobalCapabilities();

    /**
     * @return The last time that data on this server was updated
     */
    public abstract DateTime getServerLastUpdate();

    /**
     * @return All available {@link Dataset}s on this server
     */
    public abstract Collection<Dataset> getAllDatasets();

    /**
     * @param datasetId
     *            The ID of the dataset
     * @return The server-configured title of this dataset
     */
    public abstract String getDatasetTitle(String datasetId);

    /*
     * End of global server settings
     */

    /**
     * Returns a {@link Dataset} from its ID
     * 
     * @param datasetId
     *            The ID of the dataset
     * @return The desired dataset
     */
    public abstract Dataset getDatasetFromId(String datasetId);

    /**
     * Returns a {@link Dataset} based on a given layer name
     * 
     * @param layerName
     *            The full layer name
     * @return The desired dataset
     */
    public abstract Dataset getDatasetFromLayerName(String layerName)
            throws WmsLayerNotFoundException;

    /**
     * Returns a variable ID based on a given layer name
     * 
     * @param layerName
     *            The full layer name
     * @return The ID of the variable (within its {@link Dataset})
     */
    public abstract String getVariableFromId(String layerName) throws WmsLayerNotFoundException;

    /**
     * Returns the layer name based on the dataset and variable ID
     * 
     * @param dataset
     *            The ID of dataset containing the layer
     * @param variableId
     *            The ID of the variable within the dataset
     * @return The WMS layer name of this variable
     */
    public abstract String getLayerName(String datasetId, String variableId);

    /**
     * Returns server-configured metadata for a given layer
     * 
     * @param layerName
     *            The full layer name
     * @return Default metadata values for the desired layer
     */
    public abstract WmsLayerMetadata getLayerMetadata(String layerName)
            throws WmsLayerNotFoundException;
}
