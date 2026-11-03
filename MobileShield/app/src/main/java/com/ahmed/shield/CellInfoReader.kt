package com.ahmed.shield

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager

data class InfoRow(val label: String, val value: String)

data class CellCard(val title: String, val registered: Boolean, val rows: List<InfoRow>)

/** Reads the serving and neighbouring cell towers from the modem. */
object CellInfoReader {

    @SuppressLint("MissingPermission")
    fun request(ctx: Context, onResult: (List<CellInfo>) -> Unit) {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        try {
            tm.allCellInfo?.let(onResult)
            tm.requestCellInfoUpdate(ctx.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) = onResult(cellInfo)
            })
        } catch (_: SecurityException) {
        }
    }

    @SuppressLint("MissingPermission")
    fun general(ctx: Context, cells: List<CellInfo>): List<InfoRow> {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val rows = mutableListOf<InfoRow>()
        rows += InfoRow(tr("المشغّل الحالي", "Current operator"), tm.networkOperatorName.ifBlank { "—" })
        rows += InfoRow(tr("مشغّل الشريحة", "SIM operator"), tm.simOperatorName.ifBlank { "—" })
        rows += InfoRow(tr("رمز الشبكة (MCC-MNC)", "Network code (MCC-MNC)"), tm.networkOperator.ifBlank { "—" })
        val dataType = try {
            tm.dataNetworkType
        } catch (_: SecurityException) {
            -1
        }
        rows += InfoRow(
            tr("تقنية البيانات", "Data technology"),
            if (dataType < 0) tr("يحتاج إذن الهاتف", "Needs phone permission") else networkTypeName(dataType, cells.any { it is CellInfoNr })
        )
        rows += InfoRow(
            tr("حالة بيانات الجوال", "Mobile data state"),
            when (tm.dataState) {
                TelephonyManager.DATA_CONNECTED -> tr("متصل", "Connected")
                TelephonyManager.DATA_CONNECTING -> tr("جارٍ الاتصال", "Connecting")
                TelephonyManager.DATA_SUSPENDED -> tr("معلّق", "Suspended")
                else -> tr("غير متصل", "Disconnected")
            }
        )
        rows += InfoRow(tr("تجوال", "Roaming"), if (tm.isNetworkRoaming) tr("نعم", "Yes") else tr("لا", "No"))
        try {
            tm.signalStrength?.let { rows += InfoRow(tr("قوة الإشارة", "Signal strength"), tr("${it.level} من 4", "${it.level} of 4")) }
        } catch (_: Exception) {
        }
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        rows += InfoRow(
            tr("الإنترنت يمر الآن عبر", "Internet currently goes through"),
            when (NetState.current(cm)) {
                NetType.WIFI -> tr("واي فاي", "Wi-Fi")
                NetType.CELLULAR -> tr("بيانات الجوال", "Mobile data")
                NetType.OTHER -> tr("شبكة أخرى", "Other network")
                NetType.NONE -> tr("لا يوجد اتصال", "No connection")
            }
        )
        return rows
    }

    private fun networkTypeName(t: Int, hasNr: Boolean): String = when (t) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G (SA)"
        TelephonyManager.NETWORK_TYPE_LTE -> if (hasNr) "4G LTE + 5G (NSA)" else "4G LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_GSM -> "2G"
        TelephonyManager.NETWORK_TYPE_IWLAN -> tr("اتصال عبر الواي فاي (IWLAN)", "Over Wi-Fi (IWLAN)")
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> tr("غير متصل", "Disconnected")
        else -> tr("نوع $t", "Type $t")
    }

    fun cards(list: List<CellInfo>): List<CellCard> =
        list.sortedByDescending { it.isRegistered }.mapNotNull { info ->
            val rows = mutableListOf<InfoRow>()
            fun add(label: String, v: Int, unit: String = "") {
                if (v != CellInfo.UNAVAILABLE) rows += InfoRow(label, "$v$unit")
            }

            val title: String
            when (info) {
                is CellInfoNr -> {
                    title = "5G NR"
                    val id = info.cellIdentity as CellIdentityNr
                    val s = info.cellSignalStrength as CellSignalStrengthNr
                    rows += InfoRow(tr("المشغّل", "Operator"), "${id.mccString ?: "—"}-${id.mncString ?: "—"}")
                    if (id.nci != CellInfo.UNAVAILABLE_LONG) rows += InfoRow(tr("معرّف الخلية (NCI)", "Cell ID (NCI)"), id.nci.toString())
                    add("TAC", id.tac)
                    add("PCI", id.pci)
                    add("NR-ARFCN", id.nrarfcn)
                    if (Build.VERSION.SDK_INT >= 30 && id.bands.isNotEmpty()) {
                        rows += InfoRow(tr("الحزمة", "Band"), id.bands.joinToString { "n$it" })
                    }
                    add("SS-RSRP", s.ssRsrp, " dBm")
                    add("SS-RSRQ", s.ssRsrq, " dB")
                    add("SS-SINR", s.ssSinr, " dB")
                    add(tr("المستوى", "Level"), s.level, " / 4")
                }
                is CellInfoLte -> {
                    title = "4G LTE"
                    val id = info.cellIdentity
                    val s = info.cellSignalStrength
                    rows += InfoRow(tr("المشغّل", "Operator"), "${id.mccString ?: "—"}-${id.mncString ?: "—"}")
                    add(tr("معرّف الخلية (CI)", "Cell ID (CI)"), id.ci)
                    add("TAC", id.tac)
                    add("PCI", id.pci)
                    add("EARFCN", id.earfcn)
                    if (Build.VERSION.SDK_INT >= 30 && id.bands.isNotEmpty()) {
                        rows += InfoRow(tr("الحزمة", "Band"), id.bands.joinToString { "B$it" })
                    }
                    add(tr("عرض النطاق", "Bandwidth"), id.bandwidth, " kHz")
                    add("RSRP", s.rsrp, " dBm")
                    add("RSRQ", s.rsrq, " dB")
                    add("RSSNR", s.rssnr, " dB")
                    add("Timing Advance", s.timingAdvance)
                    add(tr("المستوى", "Level"), s.level, " / 4")
                }
                is CellInfoWcdma -> {
                    title = "3G WCDMA"
                    val id = info.cellIdentity
                    val s = info.cellSignalStrength
                    rows += InfoRow(tr("المشغّل", "Operator"), "${id.mccString ?: "—"}-${id.mncString ?: "—"}")
                    add(tr("معرّف الخلية (CID)", "Cell ID (CID)"), id.cid)
                    add("LAC", id.lac)
                    add("PSC", id.psc)
                    add("UARFCN", id.uarfcn)
                    add(tr("الإشارة", "Signal"), s.dbm, " dBm")
                    add(tr("المستوى", "Level"), s.level, " / 4")
                }
                is CellInfoGsm -> {
                    title = "2G GSM"
                    val id = info.cellIdentity
                    val s = info.cellSignalStrength
                    rows += InfoRow(tr("المشغّل", "Operator"), "${id.mccString ?: "—"}-${id.mncString ?: "—"}")
                    add(tr("معرّف الخلية (CID)", "Cell ID (CID)"), id.cid)
                    add("LAC", id.lac)
                    add("ARFCN", id.arfcn)
                    add(tr("الإشارة", "Signal"), s.dbm, " dBm")
                    add(tr("المستوى", "Level"), s.level, " / 4")
                }
                else -> return@mapNotNull null
            }
            CellCard(title, info.isRegistered, rows)
        }
}
