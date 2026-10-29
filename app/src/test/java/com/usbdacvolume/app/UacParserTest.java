package com.usbdacvolume.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class UacParserTest {

    @Test
    public void uac1_findsPlaybackUnitAndIgnoresMicrophone() {
        UacParser.Info info = UacParser.parse(TestDescriptors.uac1());
        assertTrue(info.supported());
        assertFalse(info.isUac2());
        assertEquals(0, info.acInterface);
        assertEquals(2, info.featureUnits.size());
        List<UacParser.FeatureUnit> pb = info.playbackUnitsWithVolume();
        assertEquals(1, pb.size());
        assertEquals(2, pb.get(0).id);
        assertEquals("[1, 2]", pb.get(0).volumeChannels.toString());
    }

    @Test
    public void uac2_parsesFourByteControls() {
        UacParser.Info info = UacParser.parse(TestDescriptors.uac2());
        assertTrue(info.supported());
        assertTrue(info.isUac2());
        List<UacParser.FeatureUnit> pb = info.playbackUnitsWithVolume();
        assertEquals(1, pb.size());
        assertEquals(2, pb.get(0).id);
        assertEquals("[1, 2]", pb.get(0).volumeChannels.toString());
    }

    @Test
    public void garbageAndNullDoNotCrash() {
        assertFalse(UacParser.parse(null).supported());
        assertFalse(UacParser.parse(new byte[0]).supported());
        assertFalse(UacParser.parse(new byte[]{9, 4, 0}).supported());
        assertFalse(UacParser.parse(new byte[]{(byte) 200, 36, 1, 2, 3}).supported());
    }
}
