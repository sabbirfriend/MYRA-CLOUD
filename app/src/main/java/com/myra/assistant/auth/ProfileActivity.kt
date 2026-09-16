package com.myra.assistant.auth

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R

class ProfileActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(40,60,40,40);setBackgroundColor(Color.rgb(3,8,19))}
        val title=TextView(this).apply{text="MYRA PROFILE";textSize=28f;setTextColor(Color.WHITE);gravity=Gravity.CENTER}
        val name=TextView(this).apply{text="Name: ${MyraAuthManager.displayName(this@ProfileActivity).ifBlank { "Google user" }}";textSize=18f;setTextColor(Color.WHITE);setPadding(0,30,0,10)}
        val email=TextView(this).apply{text="Email: ${MyraAuthManager.email(this@ProfileActivity).orEmpty()}";textSize=15f;setTextColor(Color.LTGRAY);setPadding(0,0,0,10)}
        val id=TextView(this).apply{text="User ID: ${MyraAuthManager.userId(this@ProfileActivity)}";textSize=12f;setTextColor(Color.GRAY);setPadding(0,0,0,10)}
        val role=TextView(this).apply{text=if(MyraAuthManager.isAdmin(this@ProfileActivity)) "Role: Admin" else "Role: User";textSize=14f;setTextColor(Color.CYAN)}
        val out=Button(this).apply{text="SIGN OUT";isAllCaps=false}
        box.addView(title);box.addView(name);box.addView(email);box.addView(id);box.addView(role);box.addView(out)
        setContentView(box)
        out.setOnClickListener{MyraAuthManager.signOut(this);setResult(RESULT_OK);finish()}
    }
}
