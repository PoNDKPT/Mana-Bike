package com.example.mana_bike_v001

import android.content.Context
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException

/** แปลงข้อผิดพลาดของ Firebase Auth เป็นข้อความภาษาไทย */
object AuthErrors {
    fun message(context: Context, e: Exception?): String = when (e) {
        is FirebaseAuthWeakPasswordException -> context.getString(R.string.auth_err_weak)
        is FirebaseAuthUserCollisionException -> context.getString(R.string.auth_err_exists)
        is FirebaseAuthInvalidUserException ->
            if (e.errorCode == "ERROR_USER_DISABLED") context.getString(R.string.auth_err_disabled)
            else context.getString(R.string.auth_err_invalid)
        is FirebaseAuthInvalidCredentialsException -> context.getString(R.string.auth_err_invalid)
        is FirebaseNetworkException -> context.getString(R.string.auth_err_network)
        is FirebaseTooManyRequestsException -> context.getString(R.string.auth_err_many)
        else -> context.getString(R.string.auth_err_generic, e?.localizedMessage ?: "-")
    }
}
