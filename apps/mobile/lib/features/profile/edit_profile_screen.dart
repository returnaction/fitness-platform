import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/identity_api_error.dart';
import '../auth/auth_repository.dart';
import 'profile.dart';
import 'profile_controller.dart';
import 'profile_form_rules.dart';
import 'profile_store.dart';
import 'timezone_picker.dart';

class EditProfileScreen extends ConsumerStatefulWidget {
  const EditProfileScreen({required this.initialProfile, super.key});
  final Profile initialProfile;

  @override
  ConsumerState<EditProfileScreen> createState() => _EditProfileScreenState();
}

class _EditProfileScreenState extends ConsumerState<EditProfileScreen> {
  final _formKey = GlobalKey<FormState>();
  final _fields = <String, TextEditingController>{};
  final _serverErrors = <String, String>{};
  late Profile _initial;
  String? _date;
  late String _timezone;
  late ProfileVisibility _visibility;
  String? _message;
  bool _reloadRequired = false;
  bool _submitted = false;

  @override
  void initState() {
    super.initState();
    for (final field in ['nickname', 'firstName', 'lastName', 'bio']) {
      _fields[field] = TextEditingController();
    }
    _setProfile(widget.initialProfile);
  }

  void _setProfile(Profile profile) {
    _initial = profile;
    final json = profile.toJson();
    for (final entry in _fields.entries) {
      entry.value.text = json[entry.key] as String? ?? '';
    }
    _date = profile.dateOfBirth;
    _timezone = profile.timezone;
    _visibility = profile.visibility;
    _serverErrors.clear();
    _message = null;
    _reloadRequired = false;
    _submitted = false;
  }

  Profile _draft() => normalizeProfile(Profile(
    nickname: _fields['nickname']!.text,
    firstName: _fields['firstName']!.text,
    lastName: _fields['lastName']!.text,
    bio: _fields['bio']!.text,
    dateOfBirth: _date,
    timezone: _timezone,
    visibility: _visibility,
    version: _initial.version,
  ));

  @override
  void dispose() {
    for (final field in _fields.values) { field.dispose(); }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = ref.watch(profileControllerProvider);
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final errors = _submitted ? validateProfile(_draft()) : <String, String>{};
        return Scaffold(
          appBar: AppBar(title: const Text('Edit profile')),
          body: SafeArea(
            child: Column(
              children: [
                Expanded(child: Form(
                  key: _formKey,
                  child: ListView(
                    padding: const EdgeInsets.all(24),
                    children: [
                      _textField('nickname', 'Nickname', controller.saving),
                      _textField('firstName', 'First name', controller.saving),
                      _textField('lastName', 'Last name', controller.saving),
                      _textField('bio', 'Bio', controller.saving, multiline: true),
                      ListTile(
                        contentPadding: EdgeInsets.zero,
                        title: const Text('Date of birth'),
                        subtitle: Text(_date ?? 'Not set'),
                        onTap: controller.saving ? null : _pickDate,
                        trailing: _date == null
                          ? const Icon(Icons.calendar_month)
                          : IconButton(
                              tooltip: 'Clear date of birth',
                              onPressed: controller.saving ? null : () => setState(() {
                                _date = null;
                                _serverErrors.remove('dateOfBirth');
                              }),
                              icon: const Icon(Icons.clear),
                            ),
                      ),
                      _fieldError(_serverErrors['dateOfBirth'] ?? errors['dateOfBirth']),
                      ListTile(
                        contentPadding: EdgeInsets.zero,
                        title: const Text('Timezone'),
                        subtitle: Text(_timezone),
                        trailing: const Icon(Icons.expand_more),
                        onTap: controller.saving ? null : _pickTimezone,
                      ),
                      _fieldError(_serverErrors['timezone'] ?? errors['timezone']),
                      const SizedBox(height: 12),
                      const Text('Profile visibility'),
                      RadioGroup<ProfileVisibility>(
                        groupValue: _visibility,
                        onChanged: (value) {
                          if (value != null && !controller.saving) {
                            setState(() {
                              _visibility = value;
                              _serverErrors.remove('visibility');
                            });
                          }
                        },
                        child: Column(children: [
                          RadioListTile(value: ProfileVisibility.private,
                            title: const Text('Private'), enabled: !controller.saving),
                          RadioListTile(value: ProfileVisibility.public,
                            title: const Text('Public'), enabled: !controller.saving),
                        ]),
                      ),
                      _fieldError(_serverErrors['visibility']),
                      if (_message != null) ...[
                        const SizedBox(height: 12),
                        Text(_message!, style: TextStyle(
                          color: Theme.of(context).colorScheme.error)),
                      ],
                      if (_reloadRequired)
                        TextButton(
                          onPressed: controller.loading || controller.saving ? null : _reload,
                          child: const Text('Reload profile'),
                        ),
                    ],
                  ),
                )),
                Padding(
                  padding: const EdgeInsets.fromLTRB(24, 12, 24, 16),
                  child: SizedBox(
                    width: double.infinity,
                    child: FilledButton(
                      onPressed: controller.saving || _reloadRequired ? null : _save,
                      child: controller.saving
                        ? const SizedBox(width: 20, height: 20,
                            child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('Save'),
                    ),
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _textField(String field, String label, bool saving, {bool multiline = false}) =>
      Padding(
        padding: const EdgeInsets.only(bottom: 16),
        child: TextFormField(
          key: ValueKey(field),
          controller: _fields[field],
          enabled: !saving,
          minLines: multiline ? 3 : 1,
          maxLines: multiline ? 5 : 1,
          keyboardType: multiline ? TextInputType.multiline : TextInputType.text,
          decoration: InputDecoration(labelText: label, errorText: _serverErrors[field]),
          autovalidateMode: AutovalidateMode.onUnfocus,
          validator: (_) => validateProfile(_draft())[field],
          onChanged: (_) {
            setState(() => _serverErrors.remove(field));
            if (_submitted) _formKey.currentState?.validate();
          },
        ),
      );

  Widget _fieldError(String? error) => error == null ? const SizedBox.shrink()
      : Text(error, style: TextStyle(color: Theme.of(context).colorScheme.error));

  Future<void> _pickDate() async {
    final today = profileToday(DateTime.now());
    final earliest = earliestProfileDate(today);
    var initial = _date == null ? today : DateTime.parse(_date!);
    if (initial.isBefore(earliest)) initial = earliest;
    if (initial.isAfter(today)) initial = today;
    final date = await showDatePicker(context: context, initialDate: initial,
      firstDate: earliest, lastDate: today);
    if (mounted && date != null) {
      setState(() {
        _date = profileDateString(date);
        _serverErrors.remove('dateOfBirth');
      });
    }
  }

  Future<void> _pickTimezone() async {
    final zone = await showProfileTimezonePicker(context, _timezone);
    if (mounted && zone != null) {
      setState(() {
        _timezone = zone;
        _serverErrors.remove('timezone');
      });
    }
  }

  Future<void> _reload() async {
    final controller = ref.read(profileControllerProvider);
    await controller.refresh();
    if (!mounted) return;
    setState(() {
      if (controller.error == null && controller.profile != null) {
        _setProfile(controller.profile!);
      } else {
        _message = 'Could not reload profile. Try again.';
      }
    });
  }

  Future<void> _save() async {
    FocusScope.of(context).unfocus();
    setState(() => _submitted = true);
    final draft = _draft();
    final validFields = _formKey.currentState!.validate();
    if (!validFields || validateProfile(draft).isNotEmpty) return;
    if (sameProfileValues(draft, _initial)) {
      Navigator.pop(context);
      return;
    }
    final controller = ref.read(profileControllerProvider);
    final messenger = ScaffoldMessenger.of(context);
    setState(() { _serverErrors.clear(); _message = null; });
    try {
      await controller.save(draft);
      if (mounted) Navigator.pop(context);
      if (messenger.mounted) {
        messenger.showSnackBar(const SnackBar(content: Text('Profile updated')));
      }
    } on SessionChangedException {
      // A different session must not receive notifications from this form.
    } on StaleProfileOperation {
      // Logout has already cleared this operation's state.
    } catch (error) {
      if (!mounted) {
        if (messenger.mounted) {
          messenger.showSnackBar(const SnackBar(
            content: Text('Could not confirm profile update. Open Profile to check.')));
        }
        return;
      }
      setState(() {
        if (error is IdentityApiException && error.code == 'nickname_already_taken') {
          _serverErrors['nickname'] = 'This nickname is already taken.';
        } else if (error is IdentityApiException && error.code == 'validation_error') {
          for (final detail in error.details) {
            _serverErrors[detail.field] = profileFieldError(detail.field, detail.code);
          }
          _message = 'Check the highlighted fields.';
        } else if (error is IdentityApiException && error.code == 'profile_version_conflict') {
          _message = 'Profile changed on another device. Reload it to continue.';
          _reloadRequired = true;
        } else {
          _message = controller.pendingSave
            ? 'Could not confirm whether your changes were saved. Reload your profile.'
            : 'Could not save profile. Please try again.';
          _reloadRequired = controller.pendingSave;
        }
      });
    }
  }
}
