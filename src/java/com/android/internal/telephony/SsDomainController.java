/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony;

import static android.telephony.AccessNetworkConstants.AccessNetworkType.EUTRAN;
import static android.telephony.AccessNetworkConstants.AccessNetworkType.GERAN;
import static android.telephony.AccessNetworkConstants.AccessNetworkType.IWLAN;
import static android.telephony.AccessNetworkConstants.AccessNetworkType.NGRAN;
import static android.telephony.AccessNetworkConstants.AccessNetworkType.UTRAN;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_CB_ACR;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_CB_ALL;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_CB_BAIC;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_CB_BAOC;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_CB_BIC_ROAM;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_CB_BIL;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_CB_BOIC;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_CB_BOIC_EXHC;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_CB_IBS;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_CB_OBS;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_CF_ALL;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_CF_ALL_CONDITONAL_FORWARDING;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_CF_CFB;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_CF_CFNRC;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_CF_CFNRY;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_CF_CFU;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_CW;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_IDENTIFICATION_OIP;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_IDENTIFICATION_OIR;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_IDENTIFICATION_TIP;
import static android.telephony.CarrierConfigManager.ImsSs.SUPPLEMENTARY_SERVICE_IDENTIFICATION_TIR;

import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAIC;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAICr;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAOC;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAOIC;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAOICxH;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BA_ALL;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BA_MO;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BA_MT;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_ALL;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_ALL_CONDITIONAL;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_BUSY;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_NOT_REACHABLE;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_NO_REPLY;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_UNCONDITIONAL;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.PersistableBundle;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.telephony.Rlog;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The cache of the carrier configuration
 */
public class SsDomainController {
    private static final String LOG_TAG = "SsDomainController";

    /**
     * A Helper class to carry the information indicating Ut is available or not.
     */
    public static class SuppServiceRoutingInfo {
        private final boolean mUseSsOverUt;
        private final boolean mSupportsCsfb;

        public SuppServiceRoutingInfo(boolean useSsOverUt,
                boolean isUtEnabled, boolean supportsCsfb) {
            if (useSsOverUt) {
                mUseSsOverUt = isUtEnabled;
                mSupportsCsfb = supportsCsfb;
            } else {
                mUseSsOverUt = false;
                mSupportsCsfb = true;
            }
        }

        /**
         * Returns whether Ut is available.
         */
        public boolean useSsOverUt() {
            return mUseSsOverUt;
        }

        /**
         * Returns whether CSFB is allowed.
         */
        public boolean supportsCsfb() {
            return mSupportsCsfb;
        }
    }

    public static final String SS_CW = "CW";
    public static final String SS_CLIP = "CLIP";
    public static final String SS_CLIR = "CLIR";
    public static final String SS_COLP = "COLP";
    public static final String SS_COLR = "COLR";

    // Common instance indicating that Ut is available.
    public static final SuppServiceRoutingInfo SS_ROUTING_OVER_UT =
            new SuppServiceRoutingInfo(true, true, true);

    // Barring list of incoming numbers
    public static final String CB_FACILITY_BIL = "BIL";
    // Barring of all anonymous incoming number
    public static final String CB_FACILITY_ACR = "ACR";

    /**
     * Network callback used to determine whether Wi-Fi is connected or not.
     */
    private ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Rlog.i(LOG_TAG, "Network available: " + network);
                    updateWifiForUt(true);
                }

                @Override
                public void onLost(Network network) {
                    Rlog.i(LOG_TAG, "Network lost: " + network);
                    updateWifiForUt(false);
                }

                @Override
                public void onUnavailable() {
                    Rlog.i(LOG_TAG, "Network unavailable");
                    updateWifiForUt(false);
                }
            };

    private final GsmCdmaPhone mPhone;

    private final HashSet<String> mCbOverUtSupported = new HashSet<>();
    private final HashSet<Integer> mCfOverUtSupported = new HashSet<>();
    private final HashSet<String> mSsOverUtSupported = new HashSet<>();
    private boolean mUtSupported = false;
    private boolean mCsfbSupported = true;

    private boolean mUtRequiresImsRegistration = false;
    private boolean mUtAvailableWhenPsDataOff = false;
    private boolean mUtAvailableWhenRoaming = false;
    private Set<Integer> mUtAvailableRats = new HashSet<>();
    private boolean mWiFiAvailable = false;
    private boolean mIsMonitoringConnectivity = false;

    public SsDomainController(GsmCdmaPhone phone) {
        mPhone = phone;
    }

    /**
     * Cache the configurations
     */
    public void updateSsOverUtConfig(PersistableBundle b) {
        if (b == null) {
            b = CarrierConfigManager.getDefaultConfig();
        }

        boolean supportsCsfb = b.getBoolean(
                CarrierConfigManager.ImsSs.KEY_USE_CSFB_ON_XCAP_OVER_UT_FAILURE_BOOL);
        boolean requiresImsRegistration = b.getBoolean(
                CarrierConfigManager.ImsSs.KEY_UT_REQUIRES_IMS_REGISTRATION_BOOL);
        boolean availableWhenPsDataOff = b.getBoolean(
                CarrierConfigManager.ImsSs.KEY_UT_SUPPORTED_WHEN_PS_DATA_OFF_BOOL);
        boolean availableWhenRoaming = b.getBoolean(
                CarrierConfigManager.ImsSs.KEY_UT_SUPPORTED_WHEN_ROAMING_BOOL);

        boolean supportsUt = b.getBoolean(
                CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL);
        int[] services = b.getIntArray(
                CarrierConfigManager.ImsSs.KEY_UT_SERVER_BASED_SERVICES_INT_ARRAY);

        int[] utRats = b.getIntArray(
                CarrierConfigManager.ImsSs.KEY_XCAP_OVER_UT_SUPPORTED_RATS_INT_ARRAY);

        updateSsOverUtConfig(supportsUt, supportsCsfb, requiresImsRegistration,
                availableWhenPsDataOff, availableWhenRoaming, services, utRats);
    }

    private void updateSsOverUtConfig(boolean supportsUt, boolean supportsCsfb,
            boolean requiresImsRegistration, boolean availableWhenPsDataOff,
            boolean availableWhenRoaming, int[] services, int[] utRats) {

        mUtSupported = supportsUt;
        mCsfbSupported = supportsCsfb;
        mUtRequiresImsRegistration = requiresImsRegistration;
        mUtAvailableWhenPsDataOff = availableWhenPsDataOff;
        mUtAvailableWhenRoaming = availableWhenRoaming;

        mCbOverUtSupported.clear();
        mCfOverUtSupported.clear();
        mSsOverUtSupported.clear();
        mUtAvailableRats.clear();

        if (!mUtSupported) {
            Rlog.d(LOG_TAG, "updateSsOverUtConfig Ut is not supported");
            unregisterForConnectivityChanges();
            return;
        }

        if (services != null) {
            for (int service : services) {
                updateConfig(service);
            }
        }

        if (utRats != null) {
            mUtAvailableRats = Arrays.stream(utRats).boxed().collect(Collectors.toSet());
        }

        if (mUtAvailableRats.contains(IWLAN)) {
            registerForConnectivityChanges();
        } else {
            unregisterForConnectivityChanges();
        }

        Rlog.i(LOG_TAG, "updateSsOverUtConfig supportsUt=" + mUtSupported
                + ", csfb=" + mCsfbSupported
                + ", regRequire=" + mUtRequiresImsRegistration
                + ", whenPsDataOff=" + mUtAvailableWhenPsDataOff
                + ", whenRoaming=" + mUtAvailableWhenRoaming
                + ", cbOverUtSupported=" + mCbOverUtSupported
                + ", cfOverUtSupported=" + mCfOverUtSupported
                + ", ssOverUtSupported=" + mSsOverUtSupported
                + ", utAvailableRats=" + mUtAvailableRats
                + ", including IWLAN=" + mUtAvailableRats.contains(IWLAN));
    }

    private void updateConfig(int service) {
        switch(service) {
            case SUPPLEMENTARY_SERVICE_CW: mSsOverUtSupported.add(SS_CW); return;

            case SUPPLEMENTARY_SERVICE_CF_ALL: mCfOverUtSupported.add(CF_REASON_ALL); return;
            case SUPPLEMENTARY_SERVICE_CF_CFU:
                mCfOverUtSupported.add(CF_REASON_UNCONDITIONAL);
                return;
            case SUPPLEMENTARY_SERVICE_CF_ALL_CONDITONAL_FORWARDING:
                mCfOverUtSupported.add(CF_REASON_ALL_CONDITIONAL);
                return;
            case SUPPLEMENTARY_SERVICE_CF_CFB: mCfOverUtSupported.add(CF_REASON_BUSY); return;
            case SUPPLEMENTARY_SERVICE_CF_CFNRY: mCfOverUtSupported.add(CF_REASON_NO_REPLY); return;
            case SUPPLEMENTARY_SERVICE_CF_CFNRC:
                mCfOverUtSupported.add(CF_REASON_NOT_REACHABLE);
                return;

            case SUPPLEMENTARY_SERVICE_IDENTIFICATION_OIP: mSsOverUtSupported.add(SS_CLIP); return;
            case SUPPLEMENTARY_SERVICE_IDENTIFICATION_TIP: mSsOverUtSupported.add(SS_COLP); return;
            case SUPPLEMENTARY_SERVICE_IDENTIFICATION_OIR: mSsOverUtSupported.add(SS_CLIR); return;
            case SUPPLEMENTARY_SERVICE_IDENTIFICATION_TIR: mSsOverUtSupported.add(SS_COLR); return;

            case SUPPLEMENTARY_SERVICE_CB_BAOC: mCbOverUtSupported.add(CB_FACILITY_BAOC); return;
            case SUPPLEMENTARY_SERVICE_CB_BOIC: mCbOverUtSupported.add(CB_FACILITY_BAOIC); return;
            case SUPPLEMENTARY_SERVICE_CB_BOIC_EXHC:
                mCbOverUtSupported.add(CB_FACILITY_BAOICxH);
                return;
            case SUPPLEMENTARY_SERVICE_CB_BAIC: mCbOverUtSupported.add(CB_FACILITY_BAIC); return;
            case SUPPLEMENTARY_SERVICE_CB_BIC_ROAM:
                mCbOverUtSupported.add(CB_FACILITY_BAICr);
                return;
            case SUPPLEMENTARY_SERVICE_CB_ACR: mCbOverUtSupported.add(CB_FACILITY_ACR); return;
            case SUPPLEMENTARY_SERVICE_CB_BIL: mCbOverUtSupported.add(CB_FACILITY_BIL); return;
            case SUPPLEMENTARY_SERVICE_CB_ALL: mCbOverUtSupported.add(CB_FACILITY_BA_ALL); return;
            case SUPPLEMENTARY_SERVICE_CB_OBS: mCbOverUtSupported.add(CB_FACILITY_BA_MO); return;
            case SUPPLEMENTARY_SERVICE_CB_IBS: mCbOverUtSupported.add(CB_FACILITY_BA_MT); return;

            default:
                break;
        }
    }

    /**
     * Determines whether Ut service is available or not.
     *
     * @return {@code true} if Ut service is available
     */
    @VisibleForTesting
    public boolean isUtEnabled() {
        Phone imsPhone = mPhone.getImsPhone();
        if (imsPhone == null) {
            Rlog.d(LOG_TAG, "isUtEnabled: called for GsmCdma");
            return false;
        }

        if (!mUtSupported) {
            Rlog.d(LOG_TAG, "isUtEnabled: not supported");
            return false;
        }

        if (mUtRequiresImsRegistration
                && imsPhone.getServiceState().getState() != ServiceState.STATE_IN_SERVICE) {
            Rlog.d(LOG_TAG, "isUtEnabled: not registered");
            return false;
        }

        if (isUtAvailableOnAnyTransport()) {
            return imsPhone.isUtEnabled();
        }

        return false;
    }

    private boolean isMobileDataEnabled() {
        boolean enabled;
        int state = Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                Settings.Global.MOBILE_DATA, -1);
        if (state == -1) {
            Rlog.i(LOG_TAG, "isMobileDataEnabled MOBILE_DATA not found");
            enabled = "true".equalsIgnoreCase(
                    SystemProperties.get("ro.com.android.mobiledata", "true"));
        } else {
            enabled = (state != 0);
        }
        Rlog.i(LOG_TAG, "isMobileDataEnabled enabled=" + enabled);
        return enabled;
    }

    private boolean isUtAvailableOnAnyTransport() {
        if (mUtAvailableWhenPsDataOff || isMobileDataEnabled()) {
            if (isUtAvailableOverCellular()) {
                Rlog.i(LOG_TAG, "isUtAvailableOnAnyTransport found cellular");
                return true;
            }
        }

        Rlog.i(LOG_TAG, "isUtAvailableOnAnyTransport wifiConnected=" + mWiFiAvailable);
        if (mWiFiAvailable) {
            if (mUtAvailableRats.contains(IWLAN)) {
                Rlog.i(LOG_TAG, "isUtAvailableOnAnyTransport found wifi");
                return true;
            }
            Rlog.i(LOG_TAG, "isUtAvailableOnAnyTransport wifi not support Ut");
        }

        Rlog.i(LOG_TAG, "isUtAvailableOnAnyTransport no transport");
        return false;
    }

    private boolean isUtAvailableOverCellular() {
        NetworkRegistrationInfo nri = mPhone.getServiceState().getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        if (nri != null && nri.isRegistered()) {
            if (!mUtAvailableWhenRoaming && nri.isRoaming()) {
                Rlog.i(LOG_TAG, "isUtAvailableOverCellular not available in roaming");
                return false;
            }

            int networkType = nri.getAccessNetworkTechnology();
            switch (networkType) {
                case TelephonyManager.NETWORK_TYPE_NR:
                    if (mUtAvailableRats.contains(NGRAN)) return true;
                    break;
                case TelephonyManager.NETWORK_TYPE_LTE:
                    if (mUtAvailableRats.contains(EUTRAN)) return true;
                    break;

                case TelephonyManager.NETWORK_TYPE_UMTS:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                    if (mUtAvailableRats.contains(UTRAN)) return true;
                    break;
                case TelephonyManager.NETWORK_TYPE_GPRS:
                case TelephonyManager.NETWORK_TYPE_EDGE:
                case TelephonyManager.NETWORK_TYPE_GSM:
                    if (mUtAvailableRats.contains(GERAN)) return true;
                    break;
                default:
                    break;
            }
        }

        Rlog.i(LOG_TAG, "isUtAvailableOverCellular no cellular");
        return false;
    }

    /**
     * Updates the Wi-Fi connection state.
     */
    @VisibleForTesting
    public void updateWifiForUt(boolean available) {
        mWiFiAvailable = available;
    }

    /**
     * Registers for changes to network connectivity.
     */
    private void registerForConnectivityChanges() {
        if (mIsMonitoringConnectivity) {
            return;
        }

        ConnectivityManager cm = (ConnectivityManager) mPhone.getContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            Rlog.i(LOG_TAG, "registerForConnectivityChanges");
            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
            cm.registerNetworkCallback(builder.build(), mNetworkCallback);
            mIsMonitoringConnectivity = true;
        }
    }

    /**
     * Unregisters for connectivity changes.
     */
    private void unregisterForConnectivityChanges() {
        if (!mIsMonitoringConnectivity) {
            return;
        }

        ConnectivityManager cm = (ConnectivityManager) mPhone.getContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            Rlog.i(LOG_TAG, "unregisterForConnectivityChanges");
            cm.unregisterNetworkCallback(mNetworkCallback);
            mIsMonitoringConnectivity = false;
        }
    }

    /**
     * Returns whether Ut is available for the given Call Barring service.
     */
    @VisibleForTesting
    public boolean useCbOverUt(String facility) {
        if (!mUtSupported) {
            Rlog.d(LOG_TAG, "useCbOverUt: Ut not supported");
            return false;
        }

        return mCbOverUtSupported.contains(facility);
    }

    /**
     * Returns whether Ut is available for the given Call Forwarding service.
     */
    @VisibleForTesting
    public boolean useCfOverUt(int reason) {
        if (!mUtSupported) {
            Rlog.d(LOG_TAG, "useCfOverUt: Ut not supported");
            return false;
        }

        return mCfOverUtSupported.contains(reason);
    }

    /**
     * Returns whether Ut is available for the given supplementary service.
     */
    @VisibleForTesting
    public boolean useSsOverUt(String service) {
        if (!mUtSupported) {
            Rlog.d(LOG_TAG, "useSsOverUt: Ut not supported");
            return false;
        }

        return mSsOverUtSupported.contains(service);
    }

    /**
     * Returns whether CSFB is supported for supplementary services.
     */
    public boolean supportCsfb() {
        if (!mUtSupported) {
            Rlog.d(LOG_TAG, "supportsCsfb: Ut not supported");
            return true;
        }

        return mCsfbSupported;
    }

    /**
     * Returns SuppServiceRoutingInfo instance for the given Call Barring service.
     * Only for ImsPhoneMmiCode.
     */
    public SuppServiceRoutingInfo getSuppServiceRoutingInfoForCb(String facility) {
        return new SuppServiceRoutingInfo(useCbOverUt(facility), isUtEnabled(), supportCsfb());
    }

    /**
     * Returns SuppServiceRoutingInfo instance for the given Call Forwarding service.
     * Only for ImsPhoneMmiCode.
     */
    public SuppServiceRoutingInfo getSuppServiceRoutingInfoForCf(int reason) {
        return new SuppServiceRoutingInfo(useCfOverUt(reason), isUtEnabled(), supportCsfb());
    }

    /**
     * Returns SuppServiceRoutingInfo instance for the given supplementary service.
     * Only for ImsPhoneMmiCode.
     */
    public SuppServiceRoutingInfo getSuppServiceRoutingInfoForSs(String service) {
        return new SuppServiceRoutingInfo(useSsOverUt(service), isUtEnabled(), supportCsfb());
    }

    /**
     * Set the carrier configuration for test.
     * Test purpose only.
     */
    @VisibleForTesting
    public void updateCarrierConfigForTest(boolean supportsUt, boolean supportsCsfb,
            boolean requiresImsRegistration, boolean availableWhenPsDataOff,
            boolean availableWhenRoaming, int[] services, int[] utRats) {
        Rlog.i(LOG_TAG, "updateCarrierConfigForTest supportsUt=" + supportsUt
                +  ", csfb=" + supportsCsfb
                + ", reg=" + requiresImsRegistration
                + ", whenPsDataOff=" + availableWhenPsDataOff
                + ", whenRoaming=" + availableWhenRoaming
                + ", services=" + Arrays.toString(services)
                + ", rats=" + Arrays.toString(utRats));

        updateSsOverUtConfig(supportsUt, supportsCsfb, requiresImsRegistration,
                availableWhenPsDataOff, availableWhenRoaming, services, utRats);
    }

    /**
     * Dump this instance into a readable format for dumpsys usage.
     */
    public void dump(PrintWriter printWriter) {
        IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, "  ");
        pw.increaseIndent();
        pw.println("SsDomainController:");
        pw.println(" mUtSupported=" + mUtSupported);
        pw.println(" mCsfbSupported=" + mCsfbSupported);
        pw.println(" mCbOverUtSupported=" + mCbOverUtSupported);
        pw.println(" mCfOverUtSupported=" + mCfOverUtSupported);
        pw.println(" mSsOverUtSupported=" + mSsOverUtSupported);
        pw.println(" mUtRequiresImsRegistration=" + mUtRequiresImsRegistration);
        pw.println(" mUtAvailableWhenPsDataOff=" + mUtAvailableWhenPsDataOff);
        pw.println(" mUtAvailableWhenRoaming=" + mUtAvailableWhenRoaming);
        pw.println(" mUtAvailableRats=" + mUtAvailableRats);
        pw.println(" mWiFiAvailable=" + mWiFiAvailable);
        pw.decreaseIndent();
    }
}
