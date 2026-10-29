package com.usbdacvolume.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VolumeEngineTest {
    private static final int DB = 256;

    private static FakeDac uac1Dac() {
        FakeDac d = new FakeDac(false, 0);
        int[] range = {-60 * DB, 0, DB};
        d.add(2, 1, new FakeDac.Chan(-40 * DB, range));
        d.add(2, 2, new FakeDac.Chan(-40 * DB, range));
        d.add(5, 0, new FakeDac.Chan(-5000, new int[]{-60 * DB, 0, DB})); // mikrofon: dokunulmamalı
        return d;
    }

    private static UacParser.Info uac1Info() { return UacParser.parse(TestDescriptors.uac1()); }

    @Test
    public void quietPath_setsMaxWithoutDetachingDriver() {
        FakeDac d = uac1Dac();
        VolumeEngine.Report r = VolumeEngine.apply(d, uac1Info(), VolumeEngine.Request.max(), true);
        assertTrue(r.ok);
        assertTrue(r.verified);
        assertFalse(r.soundInterrupted);
        assertEquals("kesintisiz", r.path);
        assertEquals(0, d.chan(2, 1).cur);
        assertEquals(0, d.chan(2, 2).cur);
        assertEquals(0, d.claimCount);
        assertEquals(0, d.resetCount);
        assertFalse(d.soundDown);
        assertEquals(-5000, d.chan(5, 0).cur); // mikrofon yoluna dokunulmadı
    }

    @Test
    public void quietRejected_fallsBackToClassicAndAlwaysResets() {
        FakeDac d = uac1Dac();
        d.acceptDeviceRecipient = false;
        VolumeEngine.Report r = VolumeEngine.apply(d, uac1Info(), VolumeEngine.Request.max(), true);
        assertTrue(r.ok);
        assertTrue(r.verified);
        assertTrue(r.soundInterrupted);
        assertEquals("klasik", r.path);
        assertEquals(0, d.chan(2, 1).cur);
        assertEquals(1, d.claimCount);
        assertEquals(1, d.releaseCount);
        assertEquals(1, d.resetCount);
        assertFalse(d.soundDown); // reset sonrası ses geri geldi
    }

    @Test
    public void quietDisabled_usesClassicDirectly() {
        FakeDac d = uac1Dac();
        VolumeEngine.Report r = VolumeEngine.apply(d, uac1Info(), VolumeEngine.Request.max(), false);
        assertTrue(r.ok);
        assertEquals("klasik", r.path);
        assertEquals(0, d.deviceRecipientSets);
    }

    @Test
    public void quietNeedsVerification_whenReadBackFailsFallsBack() {
        FakeDac d = uac1Dac();
        d.getCurWorks = false;
        VolumeEngine.Report r = VolumeEngine.apply(d, uac1Info(), VolumeEngine.Request.max(), true);
        assertTrue(r.ok);
        assertFalse(r.verified);          // klasikte bile geri okunamadı
        assertEquals("klasik", r.path);
        assertEquals(1, d.resetCount);
    }

    @Test
    public void maxMode_neverExceedsZeroDb_evenIfDacAllowsMore() {
        FakeDac d = new FakeDac(false, 0);
        int[] range = {-60 * DB, 6 * DB, DB}; // DAC +6 dB'ye izin veriyor
        d.add(2, 1, new FakeDac.Chan(-10 * DB, range));
        d.add(2, 2, new FakeDac.Chan(-10 * DB, range));
        VolumeEngine.apply(d, uac1Info(), VolumeEngine.Request.max(), true);
        assertEquals(0, d.chan(2, 1).cur);
    }

    @Test
    public void customDb_snapsToNearestStepAndClampsToRange() {
        FakeDac d = uac1Dac();
        VolumeEngine.apply(d, uac1Info(), VolumeEngine.Request.db(-6.4), true);
        assertEquals(-6 * DB, d.chan(2, 1).cur);
        VolumeEngine.apply(d, uac1Info(), VolumeEngine.Request.db(-200), true);
        assertEquals(-60 * DB, d.chan(2, 1).cur); // alt sınıra kırpıldı
        VolumeEngine.apply(d, uac1Info(), VolumeEngine.Request.db(+12), true);
        assertEquals(0, d.chan(2, 1).cur);        // üst sınıra kırpıldı
    }

    @Test
    public void snap_floorNeverGoesAboveDesired() {
        assertEquals(-256, VolumeEngine.snap(-100, -15360, 0, 256, true));
        assertEquals(0, VolumeEngine.snap(-100, -15360, 0, 256, false));
        assertEquals(-15360, VolumeEngine.snap(-99999, -15360, 0, 256, true));
    }

    @Test
    public void uac2_mergesSubRangesAndSetsMax() {
        FakeDac d = new FakeDac(true, 0);
        int[][] ranges = {{-60 * DB, -10 * DB, 2 * DB}, {-10 * DB, 0, DB / 2}};
        d.add(2, 1, new FakeDac.Chan(-30 * DB, ranges));
        d.add(2, 2, new FakeDac.Chan(-30 * DB, ranges));
        UacParser.Info info = UacParser.parse(TestDescriptors.uac2());
        VolumeEngine.Report r = VolumeEngine.apply(d, info, VolumeEngine.Request.max(), true);
        assertTrue(r.ok && r.verified);
        assertEquals(0, d.chan(2, 1).cur);
        assertEquals(0, d.chan(2, 2).cur);
    }

    @Test
    public void unparsableDescriptors_useLegacyUnit2() {
        FakeDac d = uac1Dac();
        VolumeEngine.Report r = VolumeEngine.apply(d, UacParser.parse(new byte[]{1, 2, 3}),
                VolumeEngine.Request.max(), true);
        assertTrue(r.ok);
        assertEquals(0, d.chan(2, 1).cur);
        assertEquals(0, d.chan(2, 2).cur);
    }

    @Test
    public void classicFailure_stillReleasesAndResetsSoSoundReturns() {
        FakeDac d = uac1Dac();
        d.acceptDeviceRecipient = false;
        d.setWorks = false;
        VolumeEngine.Report r = VolumeEngine.apply(d, uac1Info(), VolumeEngine.Request.max(), true);
        assertFalse(r.ok);
        assertEquals(1, d.releaseCount);
        assertEquals(1, d.resetCount);
        assertFalse(d.soundDown);
    }
}
