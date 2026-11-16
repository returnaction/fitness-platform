import 'package:flutter/material.dart';
import '../auth/account_screen.dart';
import '../home/home_screen.dart';
import '../profile/profile_screen.dart';

class AppShellScreen extends StatefulWidget {
  const AppShellScreen({super.key});

  @override
  State<AppShellScreen> createState() => _AppShellScreenState();
}

class _AppShellScreenState extends State<AppShellScreen> {
  int _selectedIndex = 0;

  @override
  Widget build(BuildContext context) => Scaffold(
    body: switch (_selectedIndex) {
      0 => const HomeScreen(),
      2 => const ProfileScreen(),
      _ => const AccountScreen(),
    },
    bottomNavigationBar: NavigationBar(
      selectedIndex: _selectedIndex,
      onDestinationSelected: (index) {
        if (index != 1 && _selectedIndex != index) {
          setState(() => _selectedIndex = index);
        }
      },
      destinations: const [
        NavigationDestination(
          icon: Icon(Icons.home_outlined), selectedIcon: Icon(Icons.home), label: 'Home',
        ),
        NavigationDestination(
          icon: Icon(Icons.emoji_events_outlined), label: 'Challenges', enabled: false,
        ),
        NavigationDestination(
          icon: Icon(Icons.person_outline), selectedIcon: Icon(Icons.person),
          label: 'Profile',
        ),
      ],
    ),
  );
}
