import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../auth/sign_out_button.dart';
import 'edit_profile_screen.dart';
import 'profile.dart';
import 'profile_controller.dart';

class ProfileScreen extends ConsumerStatefulWidget {
  const ProfileScreen({super.key});

  @override
  ConsumerState<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends ConsumerState<ProfileScreen> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) ref.read(profileControllerProvider).open();
    });
  }

  @override
  Widget build(BuildContext context) {
    final controller = ref.watch(profileControllerProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Profile'),
        actions: const [SignOutButton()],
      ),
      body: SafeArea(
        child: ListenableBuilder(
          listenable: controller,
          builder: (context, _) {
            final profile = controller.profile;
            if (profile == null) {
              if (controller.loading) {
                return const Center(child: CircularProgressIndicator());
              }
              return Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Text('Could not load profile.'),
                      const SizedBox(height: 12),
                      FilledButton(
                        onPressed: controller.open,
                        child: const Text('Retry'),
                      ),
                    ],
                  ),
                ),
              );
            }
            return RefreshIndicator(
              onRefresh: controller.refresh,
              child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
                children: [
                  if (controller.loading) const LinearProgressIndicator(),
                  if (controller.saving) ...[
                    const Text('Saving profile...'),
                    const LinearProgressIndicator(),
                  ] else if (controller.pendingSave || controller.needsReload) ...[
                    Text(controller.pendingSave
                      ? 'Could not confirm whether your changes were saved.'
                      : 'Profile changed on another device.'),
                    TextButton(
                      onPressed: controller.loading || controller.saving ? null : controller.refresh,
                      child: const Text('Reload profile'),
                    ),
                  ] else if (controller.error != null) ...[
                    const Text('Could not update profile. Pull down to try again.'),
                    const SizedBox(height: 16),
                  ],
                  Text(profile.nickname,
                    style: Theme.of(context).textTheme.headlineSmall),
                  const SizedBox(height: 16),
                  _Detail(label: 'First name', value: profile.firstName),
                  _Detail(label: 'Last name', value: profile.lastName),
                  if ((profile.firstName == null && profile.lastName == null) ||
                      profile.bio == null) ...[
                    const Text('Tell us about yourself by completing your profile.'),
                    const SizedBox(height: 16),
                  ],
                  _Detail(label: 'Bio', value: profile.bio),
                  _Detail(label: 'Date of birth', value: profile.dateOfBirth),
                  _Detail(label: 'Timezone', value: profile.timezone),
                  _Detail(label: 'Visibility', value:
                    profile.visibility == ProfileVisibility.public ? 'Public' : 'Private'),
                  const SizedBox(height: 8),
                  OutlinedButton(
                    onPressed: controller.saving || controller.pendingSave || controller.needsReload ? null : () {
                      Navigator.of(context).push(MaterialPageRoute<void>(
                        builder: (_) => EditProfileScreen(initialProfile: profile),
                      ));
                    },
                    child: const Text('Edit profile'),
                  ),
                ],
              ),
            );
          },
        ),
      ),
    );
  }
}

class _Detail extends StatelessWidget {
  const _Detail({required this.label, required this.value});
  final String label;
  final String? value;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 16),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: Theme.of(context).textTheme.labelLarge),
        const SizedBox(height: 4),
        Text(value == null || value!.isEmpty ? 'Not set' : value!),
      ],
    ),
  );
}
