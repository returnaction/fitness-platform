import 'package:flutter/material.dart';

import 'profile_timezones.dart';

Future<String?> showProfileTimezonePicker(BuildContext context, String selected) =>
    showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      builder: (_) => _TimezonePicker(selected: selected),
    );

class _TimezonePicker extends StatefulWidget {
  const _TimezonePicker({required this.selected});
  final String selected;
  @override
  State<_TimezonePicker> createState() => _TimezonePickerState();
}

class _TimezonePickerState extends State<_TimezonePicker> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final query = _query.trim().toLowerCase();
    final matches = profileTimezones.where((zone) =>
      zone.toLowerCase().contains(query)).toList();
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SafeArea(
        child: SizedBox(
          height: MediaQuery.sizeOf(context).height * .7,
          child: Column(
            children: [
              const Padding(
                padding: EdgeInsets.all(16), child: Text('Select timezone'),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: TextField(
                  decoration: const InputDecoration(labelText: 'Search timezone',
                    prefixIcon: Icon(Icons.search)),
                  onChanged: (value) => setState(() => _query = value),
                ),
              ),
              Expanded(child: matches.isEmpty
                ? const Center(child: Text('No timezones found.'))
                : ListView.builder(
                    itemCount: matches.length,
                    itemBuilder: (context, index) {
                      final zone = matches[index];
                      return ListTile(
                        title: Text(zone),
                        trailing: zone == widget.selected ? const Icon(Icons.check) : null,
                        onTap: () => Navigator.pop(context, zone),
                      );
                    },
                  )),
            ],
          ),
        ),
      ),
    );
  }
}
