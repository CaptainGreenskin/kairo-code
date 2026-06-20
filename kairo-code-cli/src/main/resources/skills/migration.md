---
name: migration
version: 1.0.0
category: DEVOPS
triggers:
  - "migration"
  - "migrate"
  - "/migrate"
---
# Database & Framework Migration

When performing migrations, plan for safety:

**Database Migrations**
1. Write forward migration + rollback script
2. Test on a copy of production data
3. Estimate execution time on production-sized tables
4. For large tables: use online DDL or batched updates
5. Never drop columns in the same release as the code change

**Framework Migrations**
1. Check the migration guide and changelog
2. Update dependencies incrementally, not all at once
3. Run the full test suite after each increment
4. Document breaking changes and their resolutions

**Zero-Downtime Checklist**
- [ ] Backward-compatible database changes
- [ ] Feature flags for new behavior
- [ ] Canary deployment plan
- [ ] Monitoring dashboards ready
- [ ] Rollback procedure documented and tested
