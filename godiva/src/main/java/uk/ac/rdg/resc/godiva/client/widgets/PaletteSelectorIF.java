package uk.ac.rdg.resc.godiva.client.widgets;

import java.util.List;

import com.google.gwt.user.client.ui.IsWidget;

public interface PaletteSelectorIF extends IsWidget {
    public void setId(String id);
    public void populatePalettes(List<String> availablePalettes);
    public String getSelectedPalette();
    public void selectPalette(String paletteString);
    public void populateStyles(List<String> availableStyles);
    public String getSelectedStyle();
    public void selectStyle(String styleString);
    public boolean setScaleRange(String scaleRange, Boolean logScale);
    public String getScaleRange();
    public int getNumColorBands();
    public void setNumColorBands(int nBands);
    public boolean isLogScale();
    public boolean isLocked();
    public void setEnabled(boolean enabled);
    public boolean isEnabled();
    public float getOpacity();
    public void setOpacity(float opacity);
}
