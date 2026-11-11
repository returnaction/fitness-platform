import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/fitness_app.dart';
import '../../core/network/identity_api_error.dart';
import '../../core/security/token_storage.dart';
import 'auth_controller.dart';

class AuthFormScreen extends ConsumerStatefulWidget {
  const AuthFormScreen({
    required this.isRegistration,
    this.initialEmail = '',
    super.key,
  });

  final bool isRegistration;
  final String initialEmail;

  @override
  ConsumerState<AuthFormScreen> createState() => _AuthFormScreenState();
}

class _AuthFormScreenState extends ConsumerState<AuthFormScreen> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _email;
  final _password = TextEditingController();
  late String _idempotencyKey;
  String? _registeredEmail;
  String? _registeredPassword;
  bool _showPassword = false;
  String? _message;

  @override
  void initState() {
    super.initState();
    _email = TextEditingController(text: widget.initialEmail);
    _idempotencyKey = ref.read(uuidProvider).v4();
  }

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final auth = ref.watch(authControllerProvider);
    final loading = auth.isLoading;
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          tooltip: 'Back',
          onPressed: loading ? null : () => Navigator.pop(context),
        ),
        title: Text(widget.isRegistration ? 'Create account' : 'Sign in'),
      ),
      body: SafeArea(
        child: Form(
          key: _formKey,
          child: ListView(
            padding: const EdgeInsets.all(24),
            children: [
              const SizedBox(height: 24),
              Text(
                widget.isRegistration ? 'Create your account' : 'Welcome back',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: 8),
              Text(
                widget.isRegistration
                    ? 'Enter your email and choose a password.'
                    : 'Enter your email and password to sign in.',
              ),
              const SizedBox(height: 24),
              TextFormField(
                enabled: !loading,
                controller: _email,
                keyboardType: TextInputType.emailAddress,
                autocorrect: false,
                decoration: const InputDecoration(labelText: 'Email'),
                validator: (value) => value == null || !value.contains('@')
                    ? 'Enter a valid email'
                    : null,
              ),
              const SizedBox(height: 16),
              TextFormField(
                enabled: !loading,
                controller: _password,
                obscureText: !_showPassword,
                autocorrect: false,
                enableSuggestions: false,
                decoration: const InputDecoration(labelText: 'Password'),
                validator: (value) =>
                    value == null || value.isEmpty ? 'Enter a password' : null,
              ),
              CheckboxListTile(
                contentPadding: EdgeInsets.zero,
                value: _showPassword,
                title: const Text('Show password'),
                onChanged: loading
                    ? null
                    : (value) => setState(() => _showPassword = value ?? false),
              ),
              if (_message != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: Text(
                    _message!,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ),
              FilledButton(
                onPressed: loading ? null : _submit,
                child: loading
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : Text(
                        widget.isRegistration && _canRetryRegistration
                            ? 'Try again'
                            : widget.isRegistration
                            ? 'Create account'
                            : 'Sign in',
                      ),
              ),
              const SizedBox(height: 12),
              TextButton(
                onPressed: loading ? null : _switchForm,
                child: Text(
                  widget.isRegistration
                      ? 'Already have an account? Sign in'
                      : 'New here? Create account',
                ),
              ),
              if (widget.isRegistration && _isExistingAccount)
                TextButton(
                  onPressed: loading ? null : _switchToSignIn,
                  child: const Text('Sign in instead'),
                ),
            ],
          ),
        ),
      ),
    );
  }

  bool get _isExistingAccount => _lastErrorCode == 'email_already_registered';
  bool get _canRetryRegistration =>
      _lastErrorCode == 'registration_pending' ||
      _lastErrorCode == 'registration_unavailable';

  String? _lastErrorCode;

  Future<void> _submit() async {
    if (ref.read(authControllerProvider).isLoading) {
      return;
    }
    if (!_formKey.currentState!.validate()) {
      return;
    }
    setState(() {
      _message = null;
      _lastErrorCode = null;
    });
    try {
      final controller = ref.read(authControllerProvider.notifier);
      if (widget.isRegistration) {
        _renewRegistrationKeyIfFormChanged();
        await controller.register(
          email: _email.text,
          password: _password.text,
          idempotencyKey: _idempotencyKey,
        );
      } else {
        await controller.login(email: _email.text, password: _password.text);
      }
      if (mounted) {
        Navigator.of(context).popUntil((route) => route.isFirst);
      }
    } on IdentityApiException catch (error) {
      if (mounted) {
        setState(() {
          _lastErrorCode = error.code;
          _message = _friendlyMessage(error);
        });
      }
    } on IdentityNetworkException {
      if (mounted) {
        setState(() => _message = 'Cannot connect. Please try again.');
      }
    } on TokenStorageException {
      if (mounted) {
        setState(
          () => _message =
              'Could not save your session securely. Please try again.',
        );
      }
    } on FormatException {
      if (mounted) {
        setState(() => _message = 'The server returned an invalid response.');
      }
    }
  }

  String _friendlyMessage(IdentityApiException error) {
    return switch (error.code) {
      'invalid_credentials' => 'Email or password is incorrect.',
      'email_already_registered' =>
        'An account with this email already exists.',
      'registration_pending' || 'registration_unavailable' =>
        'Your account is still being prepared. Please try again shortly.',
      'rate_limited' =>
        error.retryAfterSeconds == null
            ? 'Too many attempts. Please try again later.'
            : 'Too many attempts. Try again in ${error.retryAfterSeconds} seconds.',
      'payload_too_large' => 'The request is too large. Please try again.',
      _ => 'Request could not be completed. Please try again.',
    };
  }

  void _renewRegistrationKeyIfFormChanged() {
    if (_registeredEmail != null &&
        (_registeredEmail != _email.text ||
            _registeredPassword != _password.text)) {
      _idempotencyKey = ref.read(uuidProvider).v4();
    }
    _registeredEmail = _email.text;
    _registeredPassword = _password.text;
  }

  void _switchForm() {
    Navigator.pushReplacementNamed(
      context,
      widget.isRegistration ? AppRoute.signIn.path : AppRoute.register.path,
    );
  }

  void _switchToSignIn() {
    Navigator.pushReplacement(
      context,
      MaterialPageRoute<void>(
        builder: (_) =>
            AuthFormScreen(isRegistration: false, initialEmail: _email.text),
      ),
    );
  }
}
