package com.aimruler;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(48, 96, 48, 48);
        TextView tv = new TextView(this);
        tv.setTextSize(16);
        tv.setText("Раскидки: сначала приложение показывает, где встать, потом рисует голограмму броска.\n\n"
            + "Запись:\n"
            + "1. Нажмите «Включить», разрешите показ поверх других приложений и запись экрана.\n"
            + "2. В тренировке встаньте на позицию, бросьте гранату. Путь нарисуется жёлтой линией.\n"
            + "3. Нажмите «Запомнить».\n\n"
            + "Использование:\n"
            + "1. Кнопками ◀ и ▶ выберите раскидку. В углу появится снимок места и шкала сближения.\n"
            + "2. Идите, пока вид не станет похож на снимок: шкала станет зелёной.\n"
            + "3. Когда вы на месте, снимок исчезнет и появится бирюзовая голограмма броска.\n"
            + "4. Если «Авто» (после ◀ до начала списка), голограмма сама покажется на любой записанной точке.\n\n"
            + "Если место не узнаётся, нажмите «Совп+», если узнаётся не там, «Совп-». «Удалить» стирает выбранную раскидку.");
        Button on = new Button(this); on.setText("Включить");
        Button off = new Button(this); off.setText("Выключить");
        on.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                Toast.makeText(this, "Разрешите показ поверх других приложений и нажмите «Включить» снова", Toast.LENGTH_LONG).show();
                return;
            }
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), 100);
        });
        off.setOnClickListener(v -> stopService(new Intent(this, OverlayService.class)));
        ll.addView(tv); ll.addView(on); ll.addView(off);
        ScrollView sv = new ScrollView(this); sv.addView(ll);
        setContentView(sv);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 100 && res == RESULT_OK && data != null) {
            Intent i = new Intent(this, OverlayService.class);
            i.putExtra("code", res);
            i.putExtra("data", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            moveTaskToBack(true);
        }
    }
}
