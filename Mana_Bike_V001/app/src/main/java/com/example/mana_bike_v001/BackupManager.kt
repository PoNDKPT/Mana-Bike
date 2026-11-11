package com.example.mana_bike_v001

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** สำรอง/นำเข้าข้อมูลเป็นไฟล์ JSON เก็บไว้ที่ Google Drive, LINE Keep ฯลฯ ได้ */
object BackupManager {

    fun export(context: Context, uri: Uri): Boolean {
        val rows = DatabaseHelper.getRealInstance(context).getAllForBackup()
        val arr = JSONArray()
        for (r in rows) {
            val o = JSONObject().put("amount", r.amount).put("timestamp", r.timestamp)
            if (r.bank != null) o.put("bank", r.bank)
            if (r.channel != null) o.put("channel", r.channel)
            arr.put(o)
        }
        val root = JSONObject()
            .put("app", "ManaBike")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("transactions", arr)

        val out = context.contentResolver.openOutputStream(uri, "wt") ?: return false
        out.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
        return true
    }

    /** @return จำนวนรายการที่เพิ่มเข้ามา หรือ -1 ถ้าไฟล์ไม่ถูกต้อง */
    fun import(context: Context, uri: Uri): Int {
        val text = context.contentResolver.openInputStream(uri)
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return -1
        val root = JSONObject(text)
        if (root.optString("app") != "ManaBike") return -1
        val arr = root.getJSONArray("transactions")

        val rows = ArrayList<BackupRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            rows.add(
                BackupRow(
                    o.getDouble("amount"),
                    o.getLong("timestamp"),
                    if (o.isNull("bank")) null else o.getString("bank"),
                    if (o.isNull("channel")) null else o.getString("channel")
                )
            )
        }
        return DatabaseHelper.getRealInstance(context).importRows(rows)
    }
}
