"""
Unit tests for application start-up: configuration, database connection
failure and tracing setup.
"""

import unittest
from unittest.mock import patch

from sqlalchemy.exc import OperationalError

from userservice.tests.app_factory import build_test_app, default_env


class TestAppStartup(unittest.TestCase):
    """create_app() configuration behaviour."""

    def test_configuration_is_loaded_from_environment(self):
        """version, expiry and keys come from the environment"""
        app, client, _ = build_test_app(default_env(VERSION='v9.9', TOKEN_EXPIRY_SECONDS='120'))
        self.assertEqual(app.config['EXPIRY_SECONDS'], 120)
        self.assertIn('BEGIN', app.config['PRIVATE_KEY'])
        self.assertIn('PUBLIC KEY', app.config['PUBLIC_KEY'])
        self.assertEqual(client.get('/version').get_data(as_text=True), 'v9.9')

    def test_database_connection_failure_at_startup_exits_with_status_1(self):
        """the service refuses to start without its database"""
        failure = OperationalError('connect', {}, Exception('connection refused'))
        with patch('userservice.userservice.UserDb', side_effect=failure), \
                patch('userservice.userservice.open', create=True), \
                patch('os.environ', default_env()), \
                patch('logging.Logger.critical') as critical:
            from userservice.userservice import create_app  # pylint: disable=import-outside-toplevel
            with self.assertRaises(SystemExit) as exit_info:
                create_app()
        self.assertEqual(exit_info.exception.code, 1)
        critical.assert_called_once_with('users_db database connection failed')

    def test_tracing_enabled_instruments_flask_and_exports_to_cloud_trace(self):
        """ENABLE_TRACING=true wires the Cloud Trace exporter"""
        with patch('userservice.userservice.CloudTraceSpanExporter') as exporter, \
                patch('userservice.userservice.trace') as trace, \
                patch('userservice.userservice.set_global_textmap') as textmap, \
                patch('userservice.userservice.FlaskInstrumentor') as instrumentor:
            app, _, _ = build_test_app(default_env(ENABLE_TRACING='true'))
        exporter.assert_called_once_with()
        trace.set_tracer_provider.assert_called_once()
        textmap.assert_called_once()
        instrumentor.return_value.instrument_app.assert_called_once_with(app)

    def test_tracing_disabled_does_not_instrument(self):
        """ENABLE_TRACING=false leaves tracing off"""
        with patch('userservice.userservice.CloudTraceSpanExporter') as exporter, \
                patch('userservice.userservice.FlaskInstrumentor') as instrumentor:
            build_test_app(default_env(ENABLE_TRACING='false'))
        exporter.assert_not_called()
        instrumentor.assert_not_called()


if __name__ == '__main__':
    unittest.main()
