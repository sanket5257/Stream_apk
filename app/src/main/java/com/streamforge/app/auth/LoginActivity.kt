package com.streamforge.app.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.streamforge.app.ui.shell.ShellActivity
import com.streamforge.app.databinding.ActivityLoginBinding
import com.streamforge.app.util.CrashDialog
import com.streamforge.app.util.CrashReporter
import com.streamforge.app.util.safeLaunch

/**
 * Login/Signup screen for username/password authentication with device binding.
 */
class LoginActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityLoginBinding
    private lateinit var authManager: AuthManager
    private var isSignUpMode = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        authManager = AuthManager(this)

        // Show the previous session's crash report HERE, not only in the shell.
        //
        // When the process dies, Android relaunches the app at its launcher activity — this
        // one. Reporting only from ShellActivity meant the user had to get all the way back
        // through login before the report surfaced, and a crash that happened before that
        // point was never shown at all. This is the screen a crash actually lands on, so it
        // is the screen that has to say so.
        CrashDialog.showIfCrashed(this)

        // Wire the form up FIRST, unconditionally.
        //
        // This used to return early when a session existed, so the form's click listeners were
        // never attached on that path. That was invisible while validation always succeeded or
        // hung — but the moment a failed validation drops the user back to this form, they get
        // a Login button that does nothing at all. The form is always visible in this layout,
        // so it must always work.
        setupUI()

        if (authManager.isAuthenticated()) {
            validateAndProceed()
        }
    }
    
    private fun setupUI() {
        updateUIMode()
        
        binding.btnLogin.setOnClickListener {
            if (isSignUpMode) {
                performSignUp()
            } else {
                performLogin()
            }
        }
        
        binding.tvToggleMode.setOnClickListener {
            isSignUpMode = !isSignUpMode
            updateUIMode()
        }
        
        // Show device info
        binding.tvDeviceInfo.text = "Device: ${DeviceHelper.getDeviceName()}\n" +
                "ID: ${DeviceHelper.getDeviceId(this).take(16)}..."
    }
    
    private fun updateUIMode() {
        if (isSignUpMode) {
            // Sign Up Mode
            binding.tvTitle.text = "Create Account"
            binding.tvSubtitle.text = "Sign up to get started"
            binding.tilEmail.visibility = View.VISIBLE
            binding.tilInviteCode.visibility = View.VISIBLE
            binding.btnLogin.text = "Sign Up"
            binding.tvToggleMode.text = "Already have an account? Login"
        } else {
            // Login Mode
            binding.tvTitle.text = "Welcome Back"
            binding.tvSubtitle.text = "Login to continue"
            binding.tilEmail.visibility = View.GONE
            binding.tilInviteCode.visibility = View.GONE
            binding.btnLogin.text = "Login"
            binding.tvToggleMode.text = "Don't have an account? Sign Up"
        }
        
        // Clear errors
        binding.tilEmail.error = null
        binding.tilUsername.error = null
        binding.tilPassword.error = null
        binding.tilInviteCode.error = null
    }
    
    private fun performSignUp() {
        val email = binding.etEmail.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val inviteCode = binding.etInviteCode.text.toString().trim()
        
        // Validation
        var isValid = true
        
        if (email.isEmpty()) {
            binding.tilEmail.error = "Please enter your email"
            isValid = false
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Please enter a valid email"
            isValid = false
        } else {
            binding.tilEmail.error = null
        }
        
        if (username.isEmpty()) {
            binding.tilUsername.error = "Please enter a username"
            isValid = false
        } else if (username.length < 3) {
            binding.tilUsername.error = "Username must be at least 3 characters"
            isValid = false
        } else {
            binding.tilUsername.error = null
        }
        
        if (password.isEmpty()) {
            binding.tilPassword.error = "Please enter a password"
            isValid = false
        } else if (password.length < 6) {
            binding.tilPassword.error = "Password must be at least 6 characters"
            isValid = false
        } else {
            binding.tilPassword.error = null
        }
        
        if (inviteCode.isEmpty()) {
            binding.tilInviteCode.error = "Please enter an invite code"
            isValid = false
        } else {
            binding.tilInviteCode.error = null
        }
        
        if (!isValid) return
        
        runAuth(
            what = "signing up",
            work = { authManager.signUp(email, username, password, inviteCode) },
        ) {
            Toast.makeText(
                this@LoginActivity,
                "Account created successfully!",
                Toast.LENGTH_SHORT
            ).show()
            navigateToMain()
        }
    }
    
    private fun performLogin() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        
        if (username.isEmpty()) {
            binding.tilUsername.error = "Please enter your username"
            return
        }
        
        if (password.isEmpty()) {
            binding.tilPassword.error = "Please enter your password"
            return
        }
        
        binding.tilUsername.error = null
        binding.tilPassword.error = null
        
        runAuth(
            what = "logging in",
            work = { authManager.authenticate(username, password) },
        ) {
            Toast.makeText(
                this@LoginActivity,
                "Login successful!",
                Toast.LENGTH_SHORT
            ).show()
            navigateToMain()
        }
    }
    
    private fun validateAndProceed() {
        // Runs automatically on launch for a signed-in user, which is why a hang here was so
        // bad: it left the app on a spinner before it had shown anything at all.
        runAuth(
            what = "validating the saved session",
            work = { authManager.validateAuth() },
            errorPrefix = "Session expired: ",
        ) { navigateToMain() }
    }

    /**
     * Run one auth call with the loading overlay up, guaranteeing the overlay comes back down.
     *
     * Each call site used to clear the overlay only on the two [AuthResult] branches. Anything
     * that *threw* instead — an unreachable backend, an unreadable keystore, a TLS failure on
     * a captive-portal Wi-Fi — never reached either branch, and `safeLaunch` swallowed the
     * throw rather than crashing. The result was the app sitting on "loading" forever with
     * every field disabled and no way out but force-quitting it.
     *
     * A throw is now converted into an ordinary error result, so the user always gets the
     * overlay back, a reason, and a form they can retry from.
     */
    private fun runAuth(
        what: String,
        work: suspend () -> AuthResult,
        errorPrefix: String = "",
        onSuccess: () -> Unit,
    ) {
        setLoading(true)

        safeLaunch(TAG, what) {
            val result = try {
                work()
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "$what failed", t)
                CrashReporter.recordNonFatal(TAG, what, t)
                AuthResult.Error("Couldn't reach the server. Check your connection and try again.")
            }

            // Cleared before either branch, so no path can leave the spinner up. Harmless on
            // the success path — navigating away finishes this activity anyway.
            setLoading(false)

            when (result) {
                is AuthResult.Success -> onSuccess()
                is AuthResult.Error -> Toast.makeText(
                    this@LoginActivity,
                    "$errorPrefix${result.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private companion object {
        const val TAG = "LoginActivity"
    }

    private fun navigateToMain() {
        startActivity(Intent(this, ShellActivity::class.java))
        finish()
    }
    
    private fun setLoading(loading: Boolean) {
        // Use the new loading overlay design
        binding.loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.etEmail.isEnabled = !loading
        binding.etUsername.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
        binding.etInviteCode.isEnabled = !loading
        binding.tvToggleMode.isEnabled = !loading
    }
}
