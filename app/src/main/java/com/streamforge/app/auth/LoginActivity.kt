package com.streamforge.app.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streamforge.app.MainActivity
import com.streamforge.app.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

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
        
        // Check if already authenticated
        if (authManager.isAuthenticated()) {
            validateAndProceed()
            return
        }
        
        setupUI()
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
        
        setLoading(true)
        
        lifecycleScope.launch {
            when (val result = authManager.signUp(email, username, password, inviteCode)) {
                is AuthResult.Success -> {
                    Toast.makeText(
                        this@LoginActivity,
                        "Account created successfully!",
                        Toast.LENGTH_SHORT
                    ).show()
                    navigateToMain()
                }
                is AuthResult.Error -> {
                    setLoading(false)
                    Toast.makeText(
                        this@LoginActivity,
                        result.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
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
        
        setLoading(true)
        
        lifecycleScope.launch {
            when (val result = authManager.authenticate(username, password)) {
                is AuthResult.Success -> {
                    Toast.makeText(
                        this@LoginActivity,
                        "Login successful!",
                        Toast.LENGTH_SHORT
                    ).show()
                    navigateToMain()
                }
                is AuthResult.Error -> {
                    setLoading(false)
                    Toast.makeText(
                        this@LoginActivity,
                        result.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    private fun validateAndProceed() {
        setLoading(true)
        
        lifecycleScope.launch {
            when (val result = authManager.validateAuth()) {
                is AuthResult.Success -> {
                    navigateToMain()
                }
                is AuthResult.Error -> {
                    setLoading(false)
                    Toast.makeText(
                        this@LoginActivity,
                        "Session expired: ${result.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    // Show login form
                    binding.loginForm.visibility = View.VISIBLE
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }
    
    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
    
    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.etEmail.isEnabled = !loading
        binding.etUsername.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
        binding.etInviteCode.isEnabled = !loading
        binding.tvToggleMode.isEnabled = !loading
    }
}
