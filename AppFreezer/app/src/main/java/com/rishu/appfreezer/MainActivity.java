package com.rishu.appfreezer;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private AppAdapter adapter;
    private List<AppInfo> appList;
    private TextView tvTotalApps, tvFrozenApps, tvRamUsage, tvLastUpdate;
    private ProgressBar progressBar;
    private Button btnFreezeAll, btnRefresh, btnSettings;
    private Switch switchAutoClean;
    private LinearLayout layoutStats;
    
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateRunnable;
    private ActivityManager activityManager;
    private UsageStatsManager usageStatsManager;
    private PackageManager packageManager;
    
    private List<String> whitelistedApps = new ArrayList<>();
    private int frozenCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Initialize services
        activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        packageManager = getPackageManager();
        
        // Initialize whitelist (Important apps jo freeze nahi hone chahiye)
        initializeWhitelist();
        
        // Initialize UI
        initializeViews();
        
        // Check permissions
        checkPermissions();
        
        // Load apps
        loadBackgroundApps();
        
        // Setup auto-refresh
        setupAutoRefresh();
    }

    private void initializeViews() {
        recyclerView = findViewById(R.id.recyclerView);
        tvTotalApps = findViewById(R.id.tvTotalApps);
        tvFrozenApps = findViewById(R.id.tvFrozenApps);
        tvRamUsage = findViewById(R.id.tvRamUsage);
        tvLastUpdate = findViewById(R.id.tvLastUpdate);
        progressBar = findViewById(R.id.progressBar);
        btnFreezeAll = findViewById(R.id.btnFreezeAll);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnSettings = findViewById(R.id.btnSettings);
        switchAutoClean = findViewById(R.id.switchAutoClean);
        layoutStats = findViewById(R.id.layoutStats);
        
        appList = new ArrayList<>();
        adapter = new AppAdapter(appList);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        
        // Button listeners
        btnFreezeAll.setOnClickListener(v -> freezeAllApps());
        btnRefresh.setOnClickListener(v -> {
            loadBackgroundApps();
            Toast.makeText(this, "🔄 Refreshed!", Toast.LENGTH_SHORT).show();
        });
        btnSettings.setOnClickListener(v -> openWhitelistSettings());
        
        switchAutoClean.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                Toast.makeText(this, "✅ Auto-Clean Enabled", Toast.LENGTH_SHORT).show();
                setupAutoRefresh();
            } else {
                Toast.makeText(this, "❌ Auto-Clean Disabled", Toast.LENGTH_SHORT).show();
                stopAutoRefresh();
            }
        });
    }

    private void initializeWhitelist() {
        // Important system apps aur user apps jo freeze nahi hone chahiye
        whitelistedApps.add("com.android.systemui");
        whitelistedApps.add("com.android.launcher");
        whitelistedApps.add("com.android.phone");
        whitelistedApps.add("com.android.messaging");
        whitelistedApps.add("com.android.contacts");
        whitelistedApps.add("com.google.android.gms");
        whitelistedApps.add(getPackageName()); // Apna app
        // User add more apps in settings
    }

    private void checkPermissions() {
        if (!hasUsageStatsPermission()) {
            showPermissionDialog();
        }
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
            .setTitle("⚠️ Permission Required")
            .setMessage("App ko background apps dekhne ke liye 'Usage Access' permission chahiye.\n\n" +
                    "Settings mein jaake enable karo!")
            .setPositiveButton("Open Settings", (dialog, which) -> {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void loadBackgroundApps() {
        progressBar.setVisibility(View.VISIBLE);
        appList.clear();
        frozenCount = 0;
        
        new Thread(() -> {
            List<AppInfo> tempList = new ArrayList<>();
            
            // Get running apps using UsageStatsManager
            long endTime = System.currentTimeMillis();
            long startTime = endTime - 1000 * 60 * 5; // Last 5 minutes
            
            if (hasUsageStatsPermission() && usageStatsManager != null) {
                List<UsageStats> stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST, startTime, endTime);
                
                for (UsageStats usageStats : stats) {
                    String packageName = usageStats.getPackageName();
                    
                    // Skip whitelisted apps
                    if (whitelistedApps.contains(packageName)) continue;
                    
                    try {
                        ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
                        
                        // Only show non-system apps or running system apps
                        if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0 || 
                            usageStats.getTotalTimeInForeground() > 0) {
                            
                            AppInfo app = new AppInfo();
                            app.packageName = packageName;
                            app.appName = packageManager.getApplicationLabel(appInfo).toString();
                            app.icon = packageManager.getApplicationIcon(appInfo);
                            app.isRunning = isAppRunning(packageName);
                            app.lastUsedTime = usageStats.getLastTimeUsed();
                            
                            tempList.add(app);
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        e.printStackTrace();
                    }
                }
            }
            
            // Sort by last used time
            Collections.sort(tempList, (a, b) -> 
                Long.compare(b.lastUsedTime, a.lastUsedTime));
            
            runOnUiThread(() -> {
                appList.addAll(tempList);
                adapter.notifyDataSetChanged();
                updateStats();
                progressBar.setVisibility(View.GONE);
                updateLastUpdateTime();
            });
        }).start();
    }

    private boolean isAppRunning(String packageName) {
        List<ActivityManager.RunningAppProcessInfo> processes = 
            activityManager.getRunningAppProcesses();
        
        if (processes != null) {
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.processName.equals(packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void freezeAllApps() {
        new AlertDialog.Builder(this)
            .setTitle("❄️ Freeze Background Apps?")
            .setMessage("Sare background apps force stop ho jayenge.\n\n" +
                    "Important apps (Phone, Messages) safe rahenge!")
            .setPositiveButton("Yes, Freeze!", (dialog, which) -> {
                performFreezeAll();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void performFreezeAll() {
        frozenCount = 0;
        
        for (AppInfo app : appList) {
            if (!whitelistedApps.contains(app.packageName) && app.isRunning) {
                freezeApp(app.packageName);
                app.isRunning = false;
                frozenCount++;
            }
        }
        
        adapter.notifyDataSetChanged();
        updateStats();
        
        // Clear RAM cache
        clearRAMCache();
        
        Toast.makeText(this, "✅ " + frozenCount + " apps frozen!", 
            Toast.LENGTH_LONG).show();
    }

    private void freezeApp(String packageName) {
        try {
            activityManager.killBackgroundProcesses(packageName);
            
            // Alternative method for better force stop
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Runtime.getRuntime().exec("am force-stop " + packageName);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void clearRAMCache() {
        try {
            // System GC call
            System.gc();
            Runtime.getRuntime().gc();
            
            // Trim memory
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                activityManager.clearApplicationUserData();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateStats() {
        int runningApps = 0;
        for (AppInfo app : appList) {
            if (app.isRunning) runningApps++;
        }
        
        tvTotalApps.setText(String.valueOf(appList.size()));
        tvFrozenApps.setText(String.valueOf(frozenCount));
        
        // RAM Usage
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long usedMemory = memoryInfo.totalMem - memoryInfo.availMem;
        tvRamUsage.setText(formatBytes(usedMemory) + " / " + formatBytes(memoryInfo.totalMem));
    }

    private void updateLastUpdateTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm:ss a", Locale.getDefault());
        tvLastUpdate.setText("Updated: " + sdf.format(new Date()));
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.getDefault(), "%.1f %sB", 
            bytes / Math.pow(1024, exp), pre);
    }

    private void setupAutoRefresh() {
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                if (switchAutoClean.isChecked()) {
                    loadBackgroundApps();
                    handler.postDelayed(this, 30000); // 30 seconds
                }
            }
        };
        handler.postDelayed(updateRunnable, 30000);
    }

    private void stopAutoRefresh() {
        if (updateRunnable != null) {
            handler.removeCallbacks(updateRunnable);
        }
    }

    private void openWhitelistSettings() {
        Toast.makeText(this, "🔧 Whitelist settings coming soon!", 
            Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAutoRefresh();
    }

    // RecyclerView Adapter
    class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {
        private List<AppInfo> apps;

        AppAdapter(List<AppInfo> apps) {
            this.apps = apps;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppInfo app = apps.get(position);
            
            holder.tvAppName.setText(app.appName);
            holder.tvPackageName.setText(app.packageName);
            holder.imgIcon.setImageDrawable(app.icon);
            
            if (app.isRunning) {
                holder.tvStatus.setText("🟢 Running");
                holder.tvStatus.setTextColor(0xFF4CAF50);
                holder.btnFreeze.setEnabled(true);
                holder.btnFreeze.setText("Freeze");
            } else {
                holder.tvStatus.setText("⚪ Stopped");
                holder.tvStatus.setTextColor(0xFF9E9E9E);
                holder.btnFreeze.setEnabled(false);
                holder.btnFreeze.setText("Frozen");
            }
            
            // Time ago
            long timeAgo = System.currentTimeMillis() - app.lastUsedTime;
            holder.tvTimeAgo.setText(getTimeAgo(timeAgo));
            
            holder.btnFreeze.setOnClickListener(v -> {
                freezeApp(app.packageName);
                app.isRunning = false;
                frozenCount++;
                notifyItemChanged(position);
                updateStats();
                Toast.makeText(MainActivity.this, 
                    "❄️ " + app.appName + " frozen!", Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView imgIcon;
            TextView tvAppName, tvPackageName, tvStatus, tvTimeAgo;
            Button btnFreeze;

            ViewHolder(View itemView) {
                super(itemView);
                imgIcon = itemView.findViewById(R.id.imgIcon);
                tvAppName = itemView.findViewById(R.id.tvAppName);
                tvPackageName = itemView.findViewById(R.id.tvPackageName);
                tvStatus = itemView.findViewById(R.id.tvStatus);
                tvTimeAgo = itemView.findViewById(R.id.tvTimeAgo);
                btnFreeze = itemView.findViewById(R.id.btnFreeze);
            }
        }
    }

    private String getTimeAgo(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) return "Just now";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        return days + "d ago";
    }

    // AppInfo class
    static class AppInfo {
        String appName;
        String packageName;
        android.graphics.drawable.Drawable icon;
        boolean isRunning;
        long lastUsedTime;
    }
}
